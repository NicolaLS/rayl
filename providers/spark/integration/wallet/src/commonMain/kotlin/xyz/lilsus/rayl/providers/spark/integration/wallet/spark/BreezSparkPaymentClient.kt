package xyz.lilsus.rayl.providers.spark.integration.wallet.spark

import breez_sdk_spark.BreezSdk
import breez_sdk_spark.GetInfoRequest
import breez_sdk_spark.GetPaymentRequest
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.PaymentRequest
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.PrepareSendPaymentRequest
import breez_sdk_spark.PrepareSendPaymentResponse
import breez_sdk_spark.SdkException
import breez_sdk_spark.SendPaymentMethod
import breez_sdk_spark.SendPaymentOptions
import breez_sdk_spark.SendPaymentRequest
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPayOutcome
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPaymentClient
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPaymentProblem
import xyz.lilsus.rayl.providers.spark.application.payment.SparkQuote
import xyz.lilsus.rayl.providers.spark.application.payment.SparkQuoteResult

/**
 * Pays BOLT11 invoices through the Breez SDK. Spark invoices are not payable; see
 * [SparkPaymentClient].
 */
class BreezSparkPaymentClient(
    private val sdk: BreezSdk,
    /** The SDK started without local data, so its balance is unknown until the first sync. */
    freshStorage: Boolean,
    /** Regtest has no Lightning network, so invoices between Spark wallets settle over Spark. */
    private val preferSpark: Boolean
) : SparkPaymentClient {
    @Volatile private var awaitingInitialSync = freshStorage

    override suspend fun identityPubkey(): String =
        sdk.getInfo(GetInfoRequest(ensureSynced = false)).identityPubkey

    override suspend fun quote(invoice: String, amountSats: Long?): SparkQuoteResult = try {
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
        when (val method = response.paymentMethod) {
            is SendPaymentMethod.Bolt11Invoice ->
                SparkQuoteResult.Quoted(BreezQuote(response, method.lightningFeeSats.toLong()))

            else -> SparkQuoteResult.Rejected(
                SparkPaymentProblem.Other,
                "Unsupported payment method"
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        when (val problem = error.paymentProblem()) {
            SparkPaymentProblem.InsufficientFunds -> SparkQuoteResult.InsufficientFunds
            else -> SparkQuoteResult.Rejected(problem, error.detail())
        }
    }

    override suspend fun pay(quote: SparkQuote): SparkPayOutcome {
        val breezQuote = quote as BreezQuote
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
                SparkPaymentProblem.InsufficientFunds,
                SparkPaymentProblem.AlreadyPaid,
                SparkPaymentProblem.Expired,
                SparkPaymentProblem.BalanceSettling -> SparkPayOutcome.Failed(
                    problem,
                    error.detail()
                )

                // The SDK may have submitted the payment before failing.
                SparkPaymentProblem.Network,
                SparkPaymentProblem.Other -> SparkPayOutcome.Unknown(error.detail())
            }
        }
        return awaitOutcome(payment)
    }

    /**
     * Only with fresh storage (a new import or reinstall): before the first sync the SDK has no
     * balance, so a quote would wrongly report insufficient funds. A normal start never waits.
     */
    private suspend fun awaitInitialSync() {
        if (!awaitingInitialSync) return
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

    /** Waits a bounded time for a terminal status; a payment still pending is unknown. */
    private suspend fun awaitOutcome(initial: Payment): SparkPayOutcome {
        var payment = initial
        repeat(PENDING_POLL_ATTEMPTS) {
            when (payment.status) {
                PaymentStatus.COMPLETED -> return SparkPayOutcome.Paid(
                    feeSats = payment.fees.longValue(),
                    preimage = payment.details.paymentPreimage()
                )

                PaymentStatus.FAILED -> return SparkPayOutcome.Failed(
                    SparkPaymentProblem.Other,
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
        return SparkPayOutcome.Unknown("Payment is still pending")
    }

    private class BreezQuote(val response: PrepareSendPaymentResponse, override val feeSats: Long) :
        SparkQuote {
        val idempotencyKey = Uuid.random().toString()

        override fun toString(): String = "BreezQuote(feeSats=$feeSats)"
    }

    private companion object {
        val COMPLETION_TIMEOUT_SECONDS = 30u
        const val PENDING_POLL_ATTEMPTS = 60
        const val PENDING_POLL_MILLIS = 1_000L
        const val INITIAL_SYNC_WAIT_MILLIS = 20_000L
    }
}

/** The SDK's own text: its Kotlin exceptions format their message as `v1=<text>`. */
internal fun Exception.detail(): String? =
    message?.removePrefix("v1=")?.trim()?.takeIf(String::isNotEmpty)

/**
 * Breez reports most refusals as text. These patterns are the ones Breez's Glow app matches:
 * the gRPC status for a second send to a paid hash, and the SDK's own messages.
 */
internal fun Exception.paymentProblem(): SparkPaymentProblem {
    val text = message.orEmpty()
    return when {
        this is SdkException.InsufficientFunds -> SparkPaymentProblem.InsufficientFunds

        this is SdkException.NetworkException -> SparkPaymentProblem.Network

        "status: AlreadyExists" in text -> SparkPaymentProblem.AlreadyPaid

        // The SDK refuses these only when sending (prepare succeeds), as a SparkException:
        // "Tree service error: insufficient funds" and "… Invoice for lightning send request <id>
        // has expired" (wallet integration tests, MOB-101).
        "insufficient funds" in text.lowercase() -> SparkPaymentProblem.InsufficientFunds

        "has expired" in text -> SparkPaymentProblem.Expired

        "Failed to select leaves" in text -> SparkPaymentProblem.BalanceSettling

        // A lost connection to Breez's service provider arrives as a SparkException, not as a
        // NetworkException: "Service error: service provider error: network error: Request
        // error: …" (MOB-112).
        "network error" in text -> SparkPaymentProblem.Network

        else -> SparkPaymentProblem.Other
    }
}

internal fun PaymentDetails?.paymentPreimage(): String? = when (this) {
    is PaymentDetails.Lightning -> htlcDetails.preimage
    is PaymentDetails.Spark -> htlcDetails?.preimage
    else -> null
}?.trim()?.takeIf(String::isNotEmpty)
