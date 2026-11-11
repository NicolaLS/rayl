package xyz.lilsus.rayl.providers.blink.integration.blink

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import xyz.lilsus.raylsuite.core.model.WalletStartMode
import xyz.lilsus.raylsuite.core.settings.SecureStringStore

data object BlinkWalletConnection

data class BlinkPaymentRequest(
    val invoice: String,
    val fundingWallet: BlinkFundingWallet,
    val amount: BlinkPaymentAmount? = null
) {
    init {
        require(invoice.isNotBlank()) { "An encoded Lightning invoice cannot be blank" }
        require(
            amount == null ||
                (
                    fundingWallet.currency == BlinkWalletCurrency.BTC &&
                        amount is BlinkPaymentAmount.Bitcoin
                    ) ||
                (
                    fundingWallet.currency == BlinkWalletCurrency.USD &&
                        amount is BlinkPaymentAmount.Usd
                    )
        ) {
            "The explicit payment amount must use the funding wallet's currency"
        }
    }
}

sealed interface BlinkPaymentOutcome {
    data class Paid(val preimageHex: String?, val feesPaidMsats: Long?) : BlinkPaymentOutcome

    data object AlreadyPaid : BlinkPaymentOutcome

    data class DefinitiveFailure(val error: BlinkApiError) : BlinkPaymentOutcome

    data class StatusUnknown(val error: BlinkApiError) : BlinkPaymentOutcome
}

sealed interface BlinkConnectionError {
    data object AlreadyConnected : BlinkConnectionError

    data object MissingConnection : BlinkConnectionError

    data object ApiKeyRequired : BlinkConnectionError

    data object RequiredPermissionsMissing : BlinkConnectionError
}

class BlinkConnectionException(val error: BlinkConnectionError) : Exception(error.toString())

/** The payment half of [BlinkWallet], so a settlement can be tested without the Blink API. */
interface BlinkPayments {
    /** Throws [BlinkConnectionException] or [BlinkApiException] when no balance can pay. */
    fun prepareFundingWallet(): BlinkFundingWallet

    suspend fun submitPayment(request: BlinkPaymentRequest): BlinkPaymentOutcome
}

class BlinkWallet internal constructor(
    private val apiClient: BlinkApiClient,
    private val credentialStore: BlinkCredentialStore,
    private val isNetworkAvailable: () -> Boolean,
    startMode: WalletStartMode = WalletStartMode.Restore
) : BlinkPayments {
    private var closed = false
    private val initialCredentials = if (startMode ==
        WalletStartMode.Restore
    ) {
        credentialStore.read()
    } else {
        null
    }
    private val mutableConnection =
        MutableStateFlow(
            initialCredentials?.let { BlinkWalletConnection }
        )
    private val mutableSelectedFundingWallet =
        MutableStateFlow(initialCredentials?.selectedFundingWallet)

    val connection: StateFlow<BlinkWalletConnection?> = mutableConnection.asStateFlow()
    val selectedFundingWallet: StateFlow<BlinkFundingWallet?> =
        mutableSelectedFundingWallet.asStateFlow()

    suspend fun connect(apiKey: String) {
        check(!closed) { "Blink connection was closed" }
        ensureNotConnected()

        val normalizedApiKey = apiKey.trim()
        if (normalizedApiKey.isEmpty()) {
            throw BlinkConnectionException(BlinkConnectionError.ApiKeyRequired)
        }

        val scopes = apiClient.fetchAuthorizationScopes(normalizedApiKey)
        if (!scopes.containsAll(REQUIRED_SCOPES)) {
            throw BlinkConnectionException(BlinkConnectionError.RequiredPermissionsMissing)
        }

        val walletCatalog = apiClient.fetchFundingWallets(normalizedApiKey)
        val defaultWallet = walletCatalog.wallets.firstOrNull {
            it.id == walletCatalog.defaultWalletId
        } ?: throw BlinkApiException(
            BlinkApiError.Unexpected("Blink did not return a usable default funding wallet")
        )
        ensureNotConnected()

        val credentials = BlinkCredentials(
            apiKey = normalizedApiKey,
            selectedFundingWallet = defaultWallet
        )
        currentCoroutineContext().ensureActive()
        check(!closed) { "Blink connection was closed" }
        credentialStore.save(credentials)

        mutableSelectedFundingWallet.value = defaultWallet
        mutableConnection.value = BlinkWalletConnection
    }

    fun close() {
        if (closed) return
        closed = true
        apiClient.close()
    }

    fun disconnect() {
        credentialStore.clear()
        mutableSelectedFundingWallet.value = null
        mutableConnection.value = null
    }

    suspend fun refreshFundingWallets(): List<BlinkFundingWallet> {
        val requestCredentials = requireCredentials()
        val wallets = apiClient.fetchFundingWallets(requestCredentials.apiKey).wallets
        val credentials = requireCredentials()
        if (credentials.apiKey != requestCredentials.apiKey) {
            throw BlinkApiException(
                BlinkApiError.Unexpected(
                    "Blink connection changed while refreshing funding wallets"
                )
            )
        }
        val selectedWallet = credentials.selectedFundingWallet?.let { selection ->
            wallets.firstOrNull { wallet -> wallet == selection }
        }
        credentialStore.save(
            credentials.copy(selectedFundingWallet = selectedWallet)
        )
        mutableSelectedFundingWallet.value = selectedWallet
        return wallets
    }

    fun selectFundingWallet(wallet: BlinkFundingWallet) {
        val credentials = requireCredentials()
        credentialStore.save(
            credentials.copy(selectedFundingWallet = wallet)
        )
        mutableSelectedFundingWallet.value = wallet
    }

    override fun prepareFundingWallet(): BlinkFundingWallet =
        requireCredentials().selectedFundingWallet
            ?: throw BlinkApiException(BlinkApiError.FundingWalletUnavailable)

    suspend fun fetchContacts(): List<BlinkContact> {
        val credentials = requireCredentials()
        return apiClient.fetchContacts(credentials.apiKey)
    }

    override suspend fun submitPayment(request: BlinkPaymentRequest): BlinkPaymentOutcome {
        if (!isNetworkAvailable()) {
            return BlinkPaymentOutcome.DefinitiveFailure(BlinkApiError.NetworkUnavailable)
        }

        val credentials = if (connection.value != null) credentialStore.read() else null
        if (credentials == null) {
            return BlinkPaymentOutcome.DefinitiveFailure(
                BlinkApiError.MissingWalletConnection
            )
        }

        val result = try {
            withTimeoutOrNull(PAYMENT_RESOURCE_GUARD_MS) {
                when (val amount = request.amount) {
                    null -> apiClient.payInvoice(
                        apiKey = credentials.apiKey,
                        walletId = request.fundingWallet.id,
                        invoice = request.invoice
                    )

                    is BlinkPaymentAmount.Bitcoin -> apiClient.payNoAmountInvoice(
                        apiKey = credentials.apiKey,
                        walletId = request.fundingWallet.id,
                        invoice = request.invoice,
                        amountSats = amount.milliSatoshis.toSatsRoundedUp()
                    )

                    is BlinkPaymentAmount.Usd -> apiClient.payNoAmountUsdInvoice(
                        apiKey = credentials.apiKey,
                        walletId = request.fundingWallet.id,
                        invoice = request.invoice,
                        amountCents = amount.cents
                    )
                }
            }
        } catch (error: BlinkApiException) {
            return error.toPaymentOutcome()
        } ?: return BlinkPaymentOutcome.StatusUnknown(BlinkApiError.Timeout)

        return when (result) {
            is BlinkPaymentResult.Success ->
                BlinkPaymentOutcome.Paid(
                    preimageHex = result.preimage?.toHex(),
                    feesPaidMsats = result.feesPaid?.msat
                )

            is BlinkPaymentResult.AlreadyPaid -> BlinkPaymentOutcome.AlreadyPaid

            is BlinkPaymentResult.Pending -> awaitPaymentOutcome(
                apiKey = credentials.apiKey,
                walletId = request.fundingWallet.id,
                transactionId = result.transactionId
            )
        }
    }

    private suspend fun awaitPaymentOutcome(
        apiKey: String,
        walletId: String,
        transactionId: String?
    ): BlinkPaymentOutcome {
        if (transactionId.isNullOrBlank()) {
            return BlinkPaymentOutcome.StatusUnknown(
                BlinkApiError.Unexpected("Blink did not identify the pending payment transaction")
            )
        }

        // A payment Blink keeps pending must not hold the payment screen until the app is killed.
        return withTimeoutOrNull(PAYMENT_OUTCOME_BUDGET_MS) {
            pollPaymentOutcome(apiKey, walletId, transactionId)
        } ?: BlinkPaymentOutcome.StatusUnknown(BlinkApiError.Timeout)
    }

    private suspend fun pollPaymentOutcome(
        apiKey: String,
        walletId: String,
        transactionId: String
    ): BlinkPaymentOutcome {
        while (true) {
            delay(PAYMENT_STATUS_POLL_INTERVAL_MS)
            val outcome = try {
                apiClient.fetchPaymentOutcome(apiKey, walletId, transactionId)
            } catch (error: BlinkApiException) {
                // Failure to read a submitted payment is not evidence that the payment failed.
                return BlinkPaymentOutcome.StatusUnknown(error.error)
            }
            if (outcome != null) return outcome
        }
    }

    private fun ensureNotConnected() {
        if (mutableConnection.value != null) {
            throw BlinkConnectionException(BlinkConnectionError.AlreadyConnected)
        }
    }

    private fun requireCredentials(): BlinkCredentials {
        check(!closed) { "Blink connection was closed" }
        if (connection.value ==
            null
        ) {
            throw BlinkConnectionException(BlinkConnectionError.MissingConnection)
        }
        return credentialStore.read()
            ?: throw BlinkConnectionException(BlinkConnectionError.MissingConnection)
    }
}

fun createBlinkWallet(
    secureSettings: SecureStringStore,
    isNetworkAvailable: () -> Boolean,
    startMode: WalletStartMode
): BlinkWallet = BlinkWallet(
    apiClient = BlinkApiClient(),
    credentialStore = BlinkCredentialStore(secureSettings),
    isNetworkAvailable = isNetworkAvailable,
    startMode = startMode
)

private fun Long.toSatsRoundedUp(): Long = (this + 999L) / 1_000L

private fun BlinkApiException.toPaymentOutcome(): BlinkPaymentOutcome = when (error) {
    BlinkApiError.NetworkUnavailable,
    BlinkApiError.Timeout,
    is BlinkApiError.Unexpected -> BlinkPaymentOutcome.StatusUnknown(error)

    BlinkApiError.MissingWalletConnection,
    BlinkApiError.FundingWalletUnavailable,
    is BlinkApiError.BlinkError,
    is BlinkApiError.PaymentRejected -> BlinkPaymentOutcome.DefinitiveFailure(error)
}

private val REQUIRED_SCOPES = setOf("READ", "WRITE")
private const val PAYMENT_RESOURCE_GUARD_MS = 90_000L
private const val PAYMENT_STATUS_POLL_INTERVAL_MS = 2_000L

/** How long a pending payment is followed before its status is reported unknown; NWC waits as long. */
private const val PAYMENT_OUTCOME_BUDGET_MS = 30_000L
