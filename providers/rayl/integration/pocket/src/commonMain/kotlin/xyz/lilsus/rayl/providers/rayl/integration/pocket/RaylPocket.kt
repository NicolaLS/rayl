package xyz.lilsus.rayl.providers.rayl.integration.pocket

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class PocketStatus { Starting, Ready, Failed, Closed }

/** A payment of [amountSats] to the pocket [invoice]. */
data class PocketReceipt(val invoice: String, val amountSats: Long)

/**
 * The Rayl pocket: an app-owned Spark wallet whose key is generated on first use and kept on this
 * device. Closing it only ends the session; the key and funds survive until [erase].
 * See SPEC-wallet-lifecycle.
 */
class RaylPocket internal constructor(
    private val keys: PocketKeyStore,
    private val connector: PocketConnector,
    private val eraseWalletData: suspend () -> Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) : PocketPayments {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lifecycle = Mutex()

    @Volatile private var wallet: PocketWallet? = null
    private var eventsJob: Job? = null

    @Volatile private var closed = false

    private val mutableStatus = MutableStateFlow(PocketStatus.Starting)
    val status: StateFlow<PocketStatus> = mutableStatus.asStateFlow()

    private val mutableBalance = MutableStateFlow<Long?>(null)
    val balanceSats: StateFlow<Long?> = mutableBalance.asStateFlow()

    private val mutableReceived = MutableSharedFlow<PocketReceipt>(extraBufferCapacity = 16)

    /** Payments to invoices created by [createInvoice]. */
    val receivedPayments: SharedFlow<PocketReceipt> = mutableReceived.asSharedFlow()

    /**
     * Loads the pocket key, creating one only when none was ever stored, and connects in the
     * background. Also retries after [PocketStatus.Failed].
     */
    fun start() {
        if (closed || wallet != null) return
        PocketTimings.mark("pocket start")
        mutableStatus.value = PocketStatus.Starting
        scope.launch { connect() }
    }

    /** Waits for a pocket that is still connecting, so a payment scanned at launch is not lost. */
    override suspend fun quote(invoice: String, amountSats: Long?): PocketQuoteResult {
        val current = connectedWallet() ?: return PocketQuoteResult.NotReady
        return current.quote(invoice, amountSats)
    }

    override suspend fun pay(quote: PocketQuote): PocketPayOutcome {
        val current = wallet ?: return PocketPayOutcome.Failed("Pocket is not connected")
        return current.pay(quote).also { refreshBalance(current) }
    }

    /**
     * Returns a Lightning invoice that adds [amountSats] to the pocket, or any amount the payer
     * chooses when it is `null`.
     */
    suspend fun createInvoice(amountSats: Long?, description: String): String {
        require(amountSats == null || amountSats > 0) { "Amount must be positive" }
        val current = checkNotNull(connectedWallet()) { "Pocket is not connected" }
        return current.createInvoice(amountSats, description)
    }

    /** Ends the session and releases SDK resources. The key and wallet data remain. */
    suspend fun close() {
        closed = true
        scope.cancel()
        withContext(NonCancellable) {
            lifecycle.withLock {
                eventsJob?.cancel()
                eventsJob = null
                wallet?.disconnect()
                wallet = null
                mutableStatus.value = PocketStatus.Closed
            }
        }
    }

    /** Debug-only: closes the session and permanently deletes the key and local wallet data. */
    suspend fun erase(): Boolean {
        close()
        return withContext(NonCancellable) {
            lifecycle.withLock {
                val keyDeleted = keys.delete()
                val dataDeleted = eraseWalletData()
                keyDeleted && dataDeleted
            }
        }
    }

    internal suspend fun connect() {
        lifecycle.withLock {
            if (closed || wallet != null) return
            mutableStatus.value = PocketStatus.Starting
            val connected = try {
                val key = obtainKey()
                if (key == null) {
                    mutableStatus.value = PocketStatus.Failed
                    return
                }
                // The SDK keeps running after a cancelled connect, so always take ownership of the
                // session to be able to disconnect it.
                PocketTimings.mark("pocket key ready")
                withContext(NonCancellable) { connector.connect(key) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                PocketTimings.mark("pocket connect failed: ${error.message}")
                mutableStatus.value = PocketStatus.Failed
                return
            }
            wallet = connected
            if (closed) return
            eventsJob = scope.launch {
                connected.events.collect { event ->
                    if (event is PocketEvent.Received) {
                        mutableReceived.emit(PocketReceipt(event.invoice, event.amountSats))
                    }
                    refreshBalance(connected)
                }
            }
            PocketTimings.mark("pocket ready")
            mutableStatus.value = PocketStatus.Ready
            scope.launch { refreshBalance(connected) }
        }
    }

    /** The connected wallet once startup settles; a failed pocket is retried once per call. */
    private suspend fun connectedWallet(): PocketWallet? {
        wallet?.let { return it }
        if (mutableStatus.value == PocketStatus.Failed) start()
        withTimeoutOrNull(CONNECT_WAIT_MILLIS) {
            mutableStatus.first { it != PocketStatus.Starting }
        }
        return wallet
    }

    private suspend fun obtainKey(): PocketKey? = when (val loaded = keys.load()) {
        is PocketKeyLoad.Present -> loaded.key

        PocketKeyLoad.Unavailable -> null

        PocketKeyLoad.Absent -> {
            val generated = PocketKey.generate()
            // Connect only with the key exactly as it was durably stored.
            if (keys.add(generated)) {
                (keys.load() as? PocketKeyLoad.Present)?.key
            } else {
                null
            }
        }
    }

    private suspend fun refreshBalance(current: PocketWallet) {
        try {
            val balance = current.balanceSats()
            if (wallet === current) mutableBalance.value = balance
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Keep the last known balance; the next wallet event refreshes it.
        }
    }

    private companion object {
        const val CONNECT_WAIT_MILLIS = 30_000L
    }
}
