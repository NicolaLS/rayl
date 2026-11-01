package xyz.lilsus.rayl.providers.spark.feature.payment

import kotlinx.coroutines.CancellationException
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPayOutcome
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPaymentProblem
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPayments
import xyz.lilsus.rayl.providers.spark.application.payment.SparkQuote
import xyz.lilsus.rayl.providers.spark.application.payment.SparkQuoteResult
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentSettlement
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentUiError
import xyz.lilsus.raylsuite.feature.paymentflow.PreparedPayment
import xyz.lilsus.raylsuite.feature.paymentflow.SettleablePayment
import xyz.lilsus.raylsuite.feature.paymentflow.SettlementOutcome
import xyz.lilsus.raylsuite.feature.paymentflow.SettlementPreparation

/** An admitted invoice that the Spark wallet has funded and fee-quoted. */
class SparkPayment internal constructor(
    val request: PreparedPayment,
    internal val quote: SparkQuote
) : SettleablePayment by request

/**
 * Pays admitted BOLT11 invoices from the connected Spark wallet. Spark invoices never reach it:
 * the shared flow admits only Lightning invoices (see `SparkPaymentClient`).
 *
 * Quoting happens in [prepare], before anything is submitted, so its failures mean "not paid".
 * Once [settle] submits, an unclear result is [SettlementOutcome.Unknown]: it may have paid.
 */
class SparkPaymentSettlement(private val payments: SparkPayments) :
    PaymentSettlement<SparkPayment> {
    override suspend fun prepare(request: PreparedPayment): SettlementPreparation<SparkPayment> {
        val overrideMsats = request.amountOverrideMsats
        if (overrideMsats != null && overrideMsats % MSATS_PER_SAT != 0L) {
            return unavailable(SparkPaymentError.SubSatoshiAmount)
        }
        return when (
            val result = payments.quote(request.invoice.write(), overrideMsats?.div(MSATS_PER_SAT))
        ) {
            is SparkQuoteResult.Quoted ->
                SettlementPreparation.Ready(SparkPayment(request, result.quote))

            SparkQuoteResult.InsufficientFunds -> unavailable(SparkPaymentError.InsufficientFunds)

            SparkQuoteResult.NotReady -> unavailable(SparkPaymentError.WalletNotReady)

            is SparkQuoteResult.Rejected ->
                SettlementPreparation.Unavailable(result.problem.toUiError(result.detail))
        }
    }

    override suspend fun settle(payment: SparkPayment): SettlementOutcome {
        val outcome = try {
            payments.pay(payment.quote)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            SparkPayOutcome.Unknown(cause.message)
        }
        return when (outcome) {
            is SparkPayOutcome.Paid ->
                SettlementOutcome.Paid(
                    feeMsats = outcome.feeSats * MSATS_PER_SAT,
                    preimage = outcome.preimage
                )

            is SparkPayOutcome.Failed ->
                SettlementOutcome.Failed(outcome.problem.toUiError(outcome.detail))

            is SparkPayOutcome.Unknown ->
                SettlementOutcome.Unknown(
                    PaymentUiError.Provider(SparkPaymentError.OutcomeUnknown(outcome.detail))
                )
        }
    }

    private fun SparkPaymentProblem.toUiError(detail: String?): PaymentUiError = when (this) {
        SparkPaymentProblem.Network -> PaymentUiError.NetworkUnavailable

        SparkPaymentProblem.InsufficientFunds ->
            PaymentUiError.Provider(SparkPaymentError.InsufficientFunds)

        SparkPaymentProblem.AlreadyPaid -> PaymentUiError.Provider(SparkPaymentError.AlreadyPaid)

        SparkPaymentProblem.Expired -> PaymentUiError.Provider(SparkPaymentError.InvoiceExpired)

        SparkPaymentProblem.BalanceSettling ->
            PaymentUiError.Provider(SparkPaymentError.BalanceSettling)

        // Unrecognized refusals keep Breez's own text.
        SparkPaymentProblem.Other -> PaymentUiError.Provider(SparkPaymentError.Rejected(detail))
    }

    private fun unavailable(error: SparkPaymentError) =
        SettlementPreparation.Unavailable(PaymentUiError.Provider(error))

    private companion object {
        const val MSATS_PER_SAT = 1_000L
    }
}
