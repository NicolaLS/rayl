package xyz.lilsus.rayl.providers.rayl.integration.pocket

import breez_sdk_spark.BreezSdk
import breez_sdk_spark.ConnectRequest
import breez_sdk_spark.EventListener
import breez_sdk_spark.GetInfoRequest
import breez_sdk_spark.GetPaymentRequest
import breez_sdk_spark.InputType
import breez_sdk_spark.Network
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.PaymentRequest
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.PaymentType
import breez_sdk_spark.PrepareSendPaymentRequest
import breez_sdk_spark.PrepareSendPaymentResponse
import breez_sdk_spark.ReceivePaymentMethod
import breez_sdk_spark.ReceivePaymentRequest
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.SdkException
import breez_sdk_spark.Seed
import breez_sdk_spark.SendPaymentMethod
import breez_sdk_spark.SendPaymentOptions
import breez_sdk_spark.SendPaymentRequest
import breez_sdk_spark.connect
import breez_sdk_spark.defaultConfig
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class PocketNetwork {
    Regtest,
    Mainnet;

    /** Keys and wallet data are kept per network, so a Regtest pocket never touches real bitcoin. */
    internal val storageName: String get() = name.lowercase()
}

class PocketConfig(val network: PocketNetwork, breezApiKey: String?) {
    internal val apiKey = breezApiKey?.trim()?.takeIf(String::isNotEmpty)

    override fun toString(): String = "PocketConfig(network=$network, breezApiKey=<redacted>)"
}

internal class BreezPocketConnector(
    private val config: PocketConfig,
    /** Whether the SDK has no local data yet; checked before [storageDirectory] creates it. */
    private val storageIsEmpty: () -> Boolean,
    private val storageDirectory: () -> String
) : PocketConnector {
    override suspend fun connect(key: PocketKey): PocketWallet {
        val network = when (config.network) {
            PocketNetwork.Regtest -> Network.REGTEST
            PocketNetwork.Mainnet -> Network.MAINNET
        }
        val freshStorage = storageIsEmpty()
        val sdk = connect(
            ConnectRequest(
                // NOTE: Default leaf optimization is deliberate. It runs automatically after each
                // payment, so a deposit leaves the wallet ready for the next one-shot checkout;
                // a higher multiplicity only helps back-to-back payments and raises exit fees.
                config = defaultConfig(network).also { it.apiKey = config.apiKey },
                seed = Seed.Mnemonic(key.phrase, null),
                storageDir = storageDirectory()
            )
        )
        // Regtest has no Lightning network, so invoices between Spark wallets must settle over
        // Spark. Breez does not recommend this on Mainnet: no preimage on send, and a received
        // payment may not link back to its invoice.
        return BreezPocketWallet.attach(
            sdk,
            preferSpark = config.network == PocketNetwork.Regtest,
            freshStorage = freshStorage
        )
    }
}

private class BreezPocketWallet private constructor(
    private val sdk: BreezSdk,
    private val preferSpark: Boolean,
    freshStorage: Boolean
) : PocketWallet {
    @Volatile private var awaitingInitialSync = freshStorage

    private val mutableEvents = MutableSharedFlow<PocketEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<PocketEvent> = mutableEvents.asSharedFlow()
    private var listenerId: String? = null

    /** Payment hashes of invoices created by [createInvoice], to their invoices. */
    private val ownInvoices = LinkedHashMap<String, String>()
    private val ownInvoicesLock = Mutex()

    override suspend fun balanceSats(): Long =
        sdk.getInfo(GetInfoRequest(ensureSynced = false)).balanceSats.toLong()

    override suspend fun quote(invoice: String, amountSats: Long?): PocketQuoteResult = try {
        PocketTimings.mark("quote start")
        awaitInitialSync()
        val response = sdk.prepareSendPayment(
            PrepareSendPaymentRequest(
                paymentRequest = PaymentRequest.Input(invoice),
                amount = amountSats?.let(BigInteger::fromLong),
                tokenIdentifier = null,
                conversionOptions = null,
                feePolicy = null
            )
        )
        PocketTimings.mark("quote done")
        when (val method = response.paymentMethod) {
            is SendPaymentMethod.Bolt11Invoice -> PocketQuoteResult.Quoted(
                BreezQuote(response, feeSats = method.lightningFeeSats.toLong())
            )

            else -> PocketQuoteResult.Rejected(
                PocketPaymentProblem.Other,
                "Unsupported payment method"
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        when (val problem = error.paymentProblem()) {
            PocketPaymentProblem.InsufficientFunds -> PocketQuoteResult.InsufficientFunds
            else -> PocketQuoteResult.Rejected(problem, error.detail())
        }
    }

    override suspend fun pay(quote: PocketQuote): PocketPayOutcome {
        val breezQuote = quote as BreezQuote
        // NOTE: Rayl reports "paid" only once the payment settles, which on a warm app is most of
        // the send time. Blink self-custodial reports success as soon as sendPayment returns
        // without a completion timeout (submitted, still pending), which is faster but can show
        // success for a payment that later fails. A middle ground: wait briefly for completion,
        // then show a distinct "sent, confirming" state.
        PocketTimings.mark("send start")
        val payment = try {
            sdk.sendPayment(
                SendPaymentRequest(
                    prepareResponse = breezQuote.response,
                    options = SendPaymentOptions.Bolt11Invoice(
                        preferSpark = preferSpark,
                        completionTimeoutSecs = COMPLETION_TIMEOUT_SECONDS
                    ),
                    idempotencyKey = breezQuote.idempotencyKey
                )
            ).payment
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return when (val problem = error.paymentProblem()) {
                // Refused before anything was submitted.
                PocketPaymentProblem.InsufficientFunds,
                PocketPaymentProblem.AlreadyPaid,
                PocketPaymentProblem.Expired,
                PocketPaymentProblem.BalanceSettling -> PocketPayOutcome.Failed(
                    problem,
                    error.detail()
                )

                // The SDK may have submitted the payment before failing.
                PocketPaymentProblem.Network,
                PocketPaymentProblem.Other -> PocketPayOutcome.Unknown(error.detail())
            }
        }
        PocketTimings.mark("send returned ${payment.status.name.lowercase()}")
        return awaitOutcome(payment).also { PocketTimings.mark("payment outcome $it") }
    }

    override suspend fun createInvoice(amountSats: Long?, description: String): String {
        val invoice = sdk.receivePayment(
            ReceivePaymentRequest(
                ReceivePaymentMethod.Bolt11Invoice(
                    description = description,
                    amountSats = amountSats?.toULong(),
                    expirySecs = null,
                    paymentHash = null,
                    receiverIdentityPublicKey = null
                )
            )
        ).paymentRequest
        // Best effort: without the hash, only a Lightning settlement is recognized.
        val paymentHash = try {
            (sdk.parse(invoice) as? InputType.Bolt11Invoice)?.v1?.paymentHash
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (paymentHash != null) {
            ownInvoicesLock.withLock {
                ownInvoices[paymentHash] = invoice
                val oldest = ownInvoices.keys.first()
                if (ownInvoices.size > OWN_INVOICES_KEPT) ownInvoices.remove(oldest)
            }
        }
        return invoice
    }

    override suspend fun disconnect() {
        listenerId?.let { sdk.removeEventListener(it) }
        listenerId = null
        sdk.disconnect()
    }

    /**
     * Only with fresh storage (a new setup or reinstall): before the first sync the SDK has no
     * balance, so a quote would wrongly report insufficient funds. A normal start never waits.
     */
    private suspend fun awaitInitialSync() {
        if (!awaitingInitialSync) return
        PocketTimings.mark("awaiting initial sync")
        val synced = try {
            withTimeoutOrNull(INITIAL_SYNC_WAIT_MILLIS) {
                sdk.getInfo(GetInfoRequest(ensureSynced = true))
            } != null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        // Without a sync, quote anyway; the next quote waits again.
        if (synced) awaitingInitialSync = false
    }

    private suspend fun awaitOutcome(initial: Payment): PocketPayOutcome {
        var payment = initial
        repeat(PENDING_POLL_ATTEMPTS) {
            when (payment.status) {
                PaymentStatus.COMPLETED -> return PocketPayOutcome.Paid(
                    feeSats = payment.fees.longValue(),
                    preimage = payment.details.preimage()
                )

                PaymentStatus.FAILED -> return PocketPayOutcome.Failed(
                    PocketPaymentProblem.Other,
                    null
                )

                PaymentStatus.PENDING -> {
                    delay(PENDING_POLL_MILLIS)
                    payment = try {
                        sdk.getPayment(GetPaymentRequest(payment.id)).payment
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        payment
                    }
                }
            }
        }
        return PocketPayOutcome.Unknown("Payment is still pending")
    }

    private suspend fun listen() {
        listenerId = sdk.addEventListener(
            object : EventListener {
                override suspend fun onEvent(event: SdkEvent) {
                    PocketTimings.mark("sdk event ${event.describe()}")
                    if (event is SdkEvent.PaymentSucceeded &&
                        event.payment.paymentType == PaymentType.RECEIVE
                    ) {
                        receivedInvoice(event.payment.details)?.let {
                            mutableEvents.emit(
                                PocketEvent.Received(it, event.payment.amount.longValue())
                            )
                        }
                    }
                    mutableEvents.emit(PocketEvent.Changed)
                }
            }
        )
    }

    /**
     * The invoice a received payment paid. A Spark payer can settle an invoice through its embedded
     * Spark address instead of Lightning, which reports only the HTLC's payment hash.
     */
    private suspend fun receivedInvoice(details: PaymentDetails?): String? = when (details) {
        is PaymentDetails.Lightning -> details.invoice

        is PaymentDetails.Spark -> details.htlcDetails?.paymentHash?.let { hash ->
            ownInvoicesLock.withLock { ownInvoices[hash] }
        }

        else -> null
    }

    private class BreezQuote(val response: PrepareSendPaymentResponse, override val feeSats: Long) :
        PocketQuote {
        val idempotencyKey = Uuid.random().toString()

        override fun toString(): String = "BreezQuote(feeSats=$feeSats)"
    }

    companion object {
        suspend fun attach(
            sdk: BreezSdk,
            preferSpark: Boolean,
            freshStorage: Boolean
        ): PocketWallet {
            val wallet = BreezPocketWallet(sdk, preferSpark, freshStorage)
            try {
                wallet.listen()
            } catch (error: Throwable) {
                sdk.disconnect()
                throw error
            }
            return wallet
        }

        private val COMPLETION_TIMEOUT_SECONDS = 30u
        private const val PENDING_POLL_ATTEMPTS = 60
        private const val PENDING_POLL_MILLIS = 1_000L
        private const val OWN_INVOICES_KEPT = 16
        private const val INITIAL_SYNC_WAIT_MILLIS = 20_000L
    }
}

/** The SDK's own text: its Kotlin exceptions format their message as `v1=<text>`. */
internal fun Exception.detail(): String? =
    message?.removePrefix("v1=")?.trim()?.takeIf(String::isNotEmpty)

/**
 * Breez reports most refusals as text. These patterns are the ones Breez's Glow app matches:
 * the gRPC status for a second send to a paid hash, and the SDK's own messages.
 */
internal fun Exception.paymentProblem(): PocketPaymentProblem {
    val text = message.orEmpty()
    return when {
        this is SdkException.InsufficientFunds -> PocketPaymentProblem.InsufficientFunds

        this is SdkException.NetworkException -> PocketPaymentProblem.Network

        "status: AlreadyExists" in text -> PocketPaymentProblem.AlreadyPaid

        // The SDK refuses these only when sending (prepare succeeds), as a SparkException:
        // "Tree service error: insufficient funds" and "… Invoice for lightning send request <id>
        // has expired" (BreezSdkContractTest, MOB-101).
        "insufficient funds" in text.lowercase() -> PocketPaymentProblem.InsufficientFunds

        "has expired" in text -> PocketPaymentProblem.Expired

        "Failed to select leaves" in text -> PocketPaymentProblem.BalanceSettling

        // A lost connection to Breez's service provider arrives as a SparkException, not as a
        // NetworkException: "Service error: service provider error: network error: Request
        // error: …" (MOB-112).
        "network error" in text -> PocketPaymentProblem.Network

        else -> PocketPaymentProblem.Other
    }
}

private fun PaymentDetails?.preimage(): String? = when (this) {
    is PaymentDetails.Lightning -> htlcDetails.preimage
    is PaymentDetails.Spark -> htlcDetails?.preimage
    else -> null
}?.trim()?.takeIf(String::isNotEmpty)

private fun SdkEvent.describe(): String = when (this) {
    is SdkEvent.AutoOptimization -> "AutoOptimization $optimizationEvent"
    is SdkEvent.PaymentPending -> "PaymentPending ${payment.paymentType}"
    is SdkEvent.PaymentSucceeded -> "PaymentSucceeded ${payment.paymentType}"
    is SdkEvent.PaymentFailed -> "PaymentFailed ${payment.paymentType}"
    else -> this::class.simpleName ?: "event"
}
