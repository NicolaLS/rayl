package xyz.lilsus.rayl.providers.nwc.integration.nwc

import io.github.nicolals.nostr.nip47.model.NwcEncryption
import io.github.nicolals.nwc.Amount
import io.github.nicolals.nwc.LookupInvoiceParams
import io.github.nicolals.nwc.NwcCapability
import io.github.nicolals.nwc.NwcClient
import io.github.nicolals.nwc.NwcConnectionUri
import io.github.nicolals.nwc.NwcError
import io.github.nicolals.nwc.NwcException
import io.github.nicolals.nwc.NwcNotificationType
import io.github.nicolals.nwc.NwcResult
import io.github.nicolals.nwc.Transaction
import io.github.nicolals.nwc.TransactionState
import io.github.nicolals.nwc.WalletDiscovery
import io.github.nicolals.nwc.WalletInfo
import io.ktor.client.HttpClient
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.lilsus.raylsuite.core.model.WalletStartMode
import xyz.lilsus.raylsuite.core.network.createWebSocketHttpClient
import xyz.lilsus.raylsuite.core.settings.SecureStringStore

data class NwcWalletConnection(
    val alias: String?,
    val walletPublicKey: String,
    val relayUrls: List<String>,
    val lightningAddress: String?,
    val metadata: NwcWalletMetadata
) {
    val relayUrl: String?
        get() = relayUrls.firstOrNull()
}

data class NwcWalletDiscovery(
    val connectionUri: String,
    val walletPublicKey: String,
    val relayUrls: List<String>,
    val lightningAddress: String?,
    val aliasSuggestion: String?,
    val metadata: NwcWalletMetadata
) {
    val relayUrl: String?
        get() = relayUrls.firstOrNull()

    val supportsPayInvoice: Boolean
        get() = metadata.supports(REQUIRED_PAY_METHOD)

    val supportsLookupInvoice: Boolean
        get() = metadata.supports(REQUIRED_LOOKUP_METHOD)

    val supportsRequiredMethods: Boolean
        get() = supportsPayInvoice && supportsLookupInvoice

    val supportsNip44: Boolean
        get() = metadata.encryptionSchemes.any { it.equals("nip44_v2", ignoreCase = true) }

    val usesLegacyEncryption: Boolean
        get() = metadata.negotiatedEncryption?.equals("nip04", ignoreCase = true) == true

    /** The connection URI carries the wallet secret. */
    override fun toString(): String =
        "NwcWalletDiscovery(connectionUri=<redacted>, walletPublicKey=$walletPublicKey, " +
            "relayUrls=$relayUrls, lightningAddress=$lightningAddress, " +
            "aliasSuggestion=$aliasSuggestion, metadata=$metadata)"
}

data class NwcWalletMetadata(
    val methods: Set<String>,
    val encryptionSchemes: Set<String>,
    val negotiatedEncryption: String?,
    val encryptionDefaultedToNip04: Boolean,
    val notifications: Set<String>,
    val network: String?,
    val color: String?
) {
    fun supports(method: String): Boolean = methods.any { it.equals(method, ignoreCase = true) }
}

sealed interface NwcConnectionError {
    data object AlreadyConnected : NwcConnectionError

    data object InvalidUri : NwcConnectionError

    data object RequiredMethodsMissing : NwcConnectionError

    data class ConnectionFailed(val detail: String? = null) : NwcConnectionError
}

class NwcConnectionException(val error: NwcConnectionError, cause: Throwable? = null) :
    Exception(error.toString(), cause)

sealed interface NwcPayOutcome {
    data class Settled(val preimage: String?, val feesPaidMsats: Long?) : NwcPayOutcome

    data class WalletRejected(val code: String?, val detail: String?) : NwcPayOutcome

    data class DefinitelyNotSent(val detail: String?) : NwcPayOutcome

    data class Uncertain(val detail: String?) : NwcPayOutcome
}

/** The payment half of [NwcWallet], so a settlement can be tested without a relay. */
fun interface NwcPayments {
    suspend fun payInvoice(invoice: String, amountMsats: Long?, paymentHash: String): NwcPayOutcome
}

internal sealed interface NwcLookupOutcome {
    data class Settled(val preimage: String?, val feesPaidMsats: Long?) : NwcLookupOutcome

    data object Pending : NwcLookupOutcome

    data object Failed : NwcLookupOutcome

    data class Unavailable(val detail: String?) : NwcLookupOutcome
}

class NwcWallet internal constructor(
    private val credentialStore: NwcCredentialStore,
    private val scope: CoroutineScope,
    private val httpClient: HttpClient,
    private val isNetworkAvailable: () -> Boolean,
    startMode: WalletStartMode = WalletStartMode.Restore,
    private val ownsHttpClient: Boolean,
    private val closeClient: suspend (NwcClient) -> Unit = { it.close() }
) : NwcPayments {
    private var closed = false
    private val clientMutex = Mutex()
    private var client: NwcClient? = null
    private var closingClient: NwcClient? = null
    private val mutableConnection = MutableStateFlow(
        if (startMode ==
            WalletStartMode.Restore
        ) {
            credentialStore.read()?.toConnection()
        } else {
            null
        }
    )
    private var isInForeground = true

    val connection: StateFlow<NwcWalletConnection?> = mutableConnection.asStateFlow()

    suspend fun discover(connectionUri: String): NwcWalletDiscovery {
        check(!closed) { "Wallet experience has closed" }
        val normalizedUri = connectionUri.trim()
        if (normalizedUri.isEmpty() || NwcConnectionUri.parse(normalizedUri) == null) {
            throw NwcConnectionException(NwcConnectionError.InvalidUri)
        }
        return when (
            val result = NwcClient.discover(
                uri = normalizedUri,
                httpClient = httpClient,
                timeoutMs = DISCOVERY_TIMEOUT_MILLIS
            )
        ) {
            is NwcResult.Success -> result.value.toWalletDiscovery()
            is NwcResult.Failure -> throw result.error.toConnectionException()
        }
    }

    suspend fun connect(connectionUri: String, alias: String?): NwcWalletConnection =
        connect(discover(connectionUri), alias)

    suspend fun connect(discovery: NwcWalletDiscovery, alias: String?): NwcWalletConnection {
        if (!discovery.supportsRequiredMethods) {
            throw NwcConnectionException(NwcConnectionError.RequiredMethodsMissing)
        }
        if (mutableConnection.value != null) {
            throw NwcConnectionException(NwcConnectionError.AlreadyConnected)
        }
        val normalizedAlias = alias?.trim()?.takeIf(String::isNotEmpty)
        val uri = NwcConnectionUri.parse(discovery.connectionUri)
            ?: throw NwcConnectionException(NwcConnectionError.InvalidUri)
        val credentials = NwcCredentials(uri.raw, normalizedAlias, discovery.metadata)
        val connectedWallet = discovery.toConnection(normalizedAlias)

        clientMutex.withLock {
            if (mutableConnection.value != null) {
                throw NwcConnectionException(NwcConnectionError.AlreadyConnected)
            }
            currentCoroutineContext().ensureActive()
            check(!closed) { "Wallet experience has closed" }
            val newClient = createClient(uri, discovery.metadata)
            try {
                credentialStore.save(credentials)
                client = newClient
                mutableConnection.value = connectedWallet
            } catch (error: Throwable) {
                try {
                    credentialStore.clear()
                } finally {
                    newClient.close()
                }
                throw error
            }
        }
        return connectedWallet
    }

    suspend fun disconnect() {
        clientMutex.withLock {
            releaseClientLocked()
            credentialStore.clear()
            mutableConnection.value = null
        }
    }

    override suspend fun payInvoice(
        invoice: String,
        amountMsats: Long?,
        paymentHash: String
    ): NwcPayOutcome {
        if (!isInForeground) {
            return NwcPayOutcome.DefinitelyNotSent("App is in the background")
        }
        if (!isNetworkAvailable()) {
            return NwcPayOutcome.DefinitelyNotSent("Network unavailable")
        }
        val started = TimeSource.Monotonic.markNow()
        val activeClient = getOrCreateClient()
            ?: return NwcPayOutcome.DefinitelyNotSent("Wallet connection missing")
        return when (
            val result = activeClient.payInvoice(
                invoice = invoice,
                amount = amountMsats?.let(Amount::fromMsats),
                timeoutMs = PAY_RESPONSE_TIMEOUT_MS,
                verifyOnTimeout = false
            )
        ) {
            is NwcResult.Success ->
                NwcPayOutcome.Settled(
                    preimage = result.value.preimage,
                    feesPaidMsats = result.value.feesPaid?.msats
                )

            is NwcResult.Failure -> {
                val outcome = result.error.toPayOutcome()
                if (outcome is NwcPayOutcome.Uncertain) {
                    awaitNwcPaymentOutcome(
                        budgetMs = PAYMENT_OUTCOME_BUDGET_MS -
                            started.elapsedNow().inWholeMilliseconds,
                        lookup = { lookupInvoice(paymentHash) }
                    )
                } else {
                    outcome
                }
            }
        }
    }

    /**
     * The wallet's balance in sats, or `null` when it cannot tell: not connected, offline, or a
     * connection without `get_balance`, which NWC leaves optional.
     */
    suspend fun balanceSats(): Long? {
        if (!isInForeground || !isNetworkAvailable()) return null
        val activeClient = getOrCreateClient() ?: return null
        return activeClient.getBalance(timeoutMs = BALANCE_TIMEOUT_MS).getOrNull()?.sats
    }

    private suspend fun lookupInvoice(paymentHash: String): NwcLookupOutcome {
        if (!isInForeground) {
            return NwcLookupOutcome.Unavailable("App is in the background")
        }
        if (!isNetworkAvailable()) {
            return NwcLookupOutcome.Unavailable("Network unavailable")
        }
        val activeClient = getOrCreateClient()
            ?: return NwcLookupOutcome.Unavailable("Wallet connection missing")
        return when (
            val result = activeClient.lookupInvoice(
                params = LookupInvoiceParams(paymentHash = paymentHash),
                timeoutMs = LOOKUP_TIMEOUT_MS
            )
        ) {
            is NwcResult.Success -> result.value.toLookupOutcome()
            is NwcResult.Failure -> result.error.toLookupOutcome()
        }
    }

    suspend fun onAppForegroundChanged(isInForeground: Boolean) {
        this.isInForeground = isInForeground
        if (isInForeground) {
            if (connection.value != null) {
                getOrCreateClient()
            }
        } else {
            releaseClient()
        }
    }

    suspend fun prewarm() {
        if (isInForeground && connection.value != null) {
            getOrCreateClient()
        }
    }

    suspend fun close() {
        closed = true
        releaseClient()
        if (ownsHttpClient) httpClient.close()
    }

    private suspend fun releaseClient() {
        clientMutex.withLock { releaseClientLocked() }
    }

    private suspend fun releaseClientLocked() {
        finishClosingClient()
        closingClient = client
        client = null
        finishClosingClient()
    }

    private suspend fun finishClosingClient() = withContext(NonCancellable) {
        // collectLatest may cancel a background transition. Never make its half-closed client
        // available to the foreground, and retain failed disposal for retry before opening another.
        closingClient?.let { closeClient(it) }
        closingClient = null
    }

    private suspend fun getOrCreateClient(): NwcClient? = clientMutex.withLock {
        if (closed || connection.value == null) return@withLock null
        finishClosingClient()
        client?.let { return@withLock it }
        val credentials = credentialStore.read() ?: return@withLock null
        val uri = NwcConnectionUri.parse(credentials.connectionUri) ?: return@withLock null
        createClient(uri, credentials.metadata).also {
            client = it
        }
    }

    private fun createClient(uri: NwcConnectionUri, metadata: NwcWalletMetadata): NwcClient =
        NwcClient(
            uri = uri,
            scope = scope,
            httpClient = httpClient,
            cachedWalletInfo = metadata.toWalletInfo()
        )
}

/**
 * Looks the payment up until the wallet reports a final state, for at most [budgetMs]. A wallet
 * may report a stuck payment as pending for hours; Rayl then shows the outcome as unknown rather
 * than keep the user on "Paying" (MOB-9). Nothing is resubmitted either way.
 */
internal suspend fun awaitNwcPaymentOutcome(
    budgetMs: Long,
    lookup: suspend () -> NwcLookupOutcome
): NwcPayOutcome = withTimeoutOrNull(budgetMs.coerceAtLeast(0)) { lookUpUntilFinal(lookup) }
    // No detail: the shown message is the translated "status unknown" text alone.
    ?: NwcPayOutcome.Uncertain(detail = null)

private suspend fun lookUpUntilFinal(lookup: suspend () -> NwcLookupOutcome): NwcPayOutcome {
    var observedPending = false
    while (true) {
        when (val outcome = lookup()) {
            is NwcLookupOutcome.Settled ->
                return NwcPayOutcome.Settled(outcome.preimage, outcome.feesPaidMsats)

            NwcLookupOutcome.Failed -> {
                // Hash lookup may return an earlier failed attempt after a lost send response.
                return if (observedPending) {
                    NwcPayOutcome.WalletRejected("LOOKUP_FAILED", "Payment failed")
                } else {
                    NwcPayOutcome.Uncertain("The submitted payment could not be confirmed")
                }
            }

            is NwcLookupOutcome.Unavailable -> return NwcPayOutcome.Uncertain(outcome.detail)

            NwcLookupOutcome.Pending -> observedPending = true
        }
        delay(PAYMENT_STATUS_POLL_INTERVAL_MS)
    }
}

fun createNwcWallet(
    secureSettings: SecureStringStore,
    scope: CoroutineScope,
    isNetworkAvailable: () -> Boolean,
    startMode: WalletStartMode
): NwcWallet {
    val httpClient = createWebSocketHttpClient()
    return NwcWallet(
        credentialStore = NwcCredentialStore(secureSettings),
        scope = scope,
        httpClient = httpClient,
        isNetworkAvailable = isNetworkAvailable,
        startMode = startMode,
        ownsHttpClient = true
    )
}

fun createNwcWallet(
    secureSettings: SecureStringStore,
    scope: CoroutineScope,
    httpClient: HttpClient,
    isNetworkAvailable: () -> Boolean,
    startMode: WalletStartMode
): NwcWallet = NwcWallet(
    credentialStore = NwcCredentialStore(secureSettings),
    scope = scope,
    httpClient = httpClient,
    isNetworkAvailable = isNetworkAvailable,
    startMode = startMode,
    ownsHttpClient = false
)

fun isValidNwcConnectionUri(value: String): Boolean = NwcConnectionUri.isValid(value.trim())

private fun NwcCredentials.toConnection(): NwcWalletConnection? {
    val uri = NwcConnectionUri.parse(connectionUri) ?: return null
    return NwcWalletConnection(
        alias = alias,
        walletPublicKey = uri.walletPubkey.hex,
        relayUrls = uri.relays,
        lightningAddress = uri.lud16,
        metadata = metadata
    )
}

private fun WalletDiscovery.toWalletDiscovery(): NwcWalletDiscovery = NwcWalletDiscovery(
    connectionUri = uri.raw,
    walletPublicKey = uri.walletPubkey.hex,
    relayUrls = uri.relays,
    lightningAddress = uri.lud16,
    aliasSuggestion = details?.alias,
    metadata = NwcWalletMetadata(
        methods = walletInfo.capabilityStrings,
        encryptionSchemes = walletInfo.encryptionStrings,
        negotiatedEncryption = walletInfo.preferredEncryption.tag,
        encryptionDefaultedToNip04 = walletInfo.encryptionDefaultedToNip04,
        notifications = walletInfo.notificationStrings,
        network = details?.network,
        color = details?.color
    )
)

private fun NwcWalletDiscovery.toConnection(alias: String?): NwcWalletConnection =
    NwcWalletConnection(
        alias = alias,
        walletPublicKey = walletPublicKey,
        relayUrls = relayUrls,
        lightningAddress = lightningAddress,
        metadata = metadata
    )

private fun NwcWalletMetadata.toWalletInfo(): WalletInfo {
    val capabilities = methods.mapNotNull(NwcCapability::fromValue).toSet()
    val notificationTypes = notifications.mapNotNull(NwcNotificationType::fromValue).toSet()
    val encryptions = encryptionSchemes.mapNotNull(NwcEncryption::fromTag).toSet()
    val preferredEncryption = negotiatedEncryption?.let(NwcEncryption::fromTag)
        ?: when {
            NwcEncryption.NIP44_V2 in encryptions -> NwcEncryption.NIP44_V2
            else -> NwcEncryption.NIP04
        }
    return WalletInfo(
        capabilities = capabilities,
        notifications = notificationTypes,
        encryptions = encryptions.ifEmpty { setOf(NwcEncryption.NIP04) },
        preferredEncryption = preferredEncryption,
        encryptionDefaultedToNip04 = encryptionDefaultedToNip04
    )
}

private fun Transaction.toLookupOutcome(): NwcLookupOutcome = when (state) {
    TransactionState.SETTLED ->
        NwcLookupOutcome.Settled(preimage = preimage, feesPaidMsats = feesPaid?.msats)

    TransactionState.PENDING -> NwcLookupOutcome.Pending

    TransactionState.FAILED, TransactionState.EXPIRED -> NwcLookupOutcome.Failed

    null ->
        if (settledAt != null || preimage != null) {
            NwcLookupOutcome.Settled(preimage = preimage, feesPaidMsats = feesPaid?.msats)
        } else {
            NwcLookupOutcome.Pending
        }
}

private fun NwcError.toPayOutcome(): NwcPayOutcome = when (this) {
    is NwcError.WalletError ->
        NwcPayOutcome.WalletRejected(
            code = code.code.takeIf(String::isNotEmpty),
            detail = message.takeIf(String::isNotEmpty)
        )

    is NwcError.ConnectionError -> NwcPayOutcome.DefinitelyNotSent(message)

    is NwcError.Timeout -> NwcPayOutcome.Uncertain(message)

    is NwcError.PaymentPending -> NwcPayOutcome.Uncertain(message)

    is NwcError.Cancelled -> NwcPayOutcome.Uncertain(message)

    is NwcError.ProtocolError -> NwcPayOutcome.Uncertain(message)

    is NwcError.CryptoError -> NwcPayOutcome.DefinitelyNotSent(message)
}

private fun NwcError.toLookupOutcome(): NwcLookupOutcome = when (this) {
    is NwcError.PaymentPending -> NwcLookupOutcome.Pending
    else -> NwcLookupOutcome.Unavailable(message)
}

private fun NwcError.toConnectionException(): NwcConnectionException = when (this) {
    is NwcError.ProtocolError ->
        NwcConnectionException(
            if (message.contains("invalid", ignoreCase = true)) {
                NwcConnectionError.InvalidUri
            } else {
                NwcConnectionError.ConnectionFailed(message)
            },
            cause
        )

    is NwcError.ConnectionError ->
        NwcConnectionException(NwcConnectionError.ConnectionFailed(message), cause)

    else ->
        NwcConnectionException(
            NwcConnectionError.ConnectionFailed(message),
            NwcException(this)
        )
}

private const val DISCOVERY_TIMEOUT_MILLIS = 10_000L
private const val REQUIRED_PAY_METHOD = "pay_invoice"
private const val REQUIRED_LOOKUP_METHOD = "lookup_invoice"

private const val PAY_RESPONSE_TIMEOUT_MS = 15_000L
private const val LOOKUP_TIMEOUT_MS = 10_000L
private const val BALANCE_TIMEOUT_MS = 10_000L
private const val PAYMENT_STATUS_POLL_INTERVAL_MS = 2_000L

/**
 * From sending a payment to showing its outcome, lookups included (MOB-9). A user who waits
 * longer assumes the app is stuck and restarts it.
 */
private const val PAYMENT_OUTCOME_BUDGET_MS = 30_000L
