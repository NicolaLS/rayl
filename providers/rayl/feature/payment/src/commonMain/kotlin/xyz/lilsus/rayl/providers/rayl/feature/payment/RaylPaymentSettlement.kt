package xyz.lilsus.rayl.providers.rayl.feature.payment

import kotlinx.coroutines.CancellationException
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketPayOutcome
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketPaymentProblem
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketPayments
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketQuote
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketQuoteResult
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketTimings
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentSettlement
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentUiError
import xyz.lilsus.raylsuite.feature.paymentflow.PreparedPayment
import xyz.lilsus.raylsuite.feature.paymentflow.SettleablePayment
import xyz.lilsus.raylsuite.feature.paymentflow.SettlementOutcome
import xyz.lilsus.raylsuite.feature.paymentflow.SettlementPreparation

/** An admitted invoice that the pocket has funded and fee-quoted. */
class RaylPayment internal constructor(
    val request: PreparedPayment,
    internal val quote: PocketQuote
) : SettleablePayment by request

/**
 * Pays admitted invoices from the Rayl pocket.
 *
 * Waiting for the pocket to connect happens in [prepare], before anything is submitted, so its
 * failures mean "not paid" and are safe to retry. Once [settle] submits, an unclear result is
 * [SettlementOutcome.Unknown] and must never be retried or shown as failed: it may have paid.
 */
class RaylPaymentSettlement(private val pocket: PocketPayments) : PaymentSettlement<RaylPayment> {
    override suspend fun prepare(request: PreparedPayment): SettlementPreparation<RaylPayment> {
        PocketTimings.mark("payment admitted (${request.source})")
        val overrideMsats = request.amountOverrideMsats
        if (overrideMsats != null && overrideMsats % MSATS_PER_SAT != 0L) {
            return unavailable(RaylPaymentError.SubSatoshiAmount)
        }
        return when (
            val result = pocket.quote(
                request.invoice.write(),
                overrideMsats?.div(MSATS_PER_SAT)
            )
        ) {
            is PocketQuoteResult.Quoted ->
                SettlementPreparation.Ready(RaylPayment(request, result.quote))

            PocketQuoteResult.InsufficientFunds -> unavailable(RaylPaymentError.InsufficientFunds)

            PocketQuoteResult.NotReady -> unavailable(RaylPaymentError.PocketNotReady)

            is PocketQuoteResult.Rejected ->
                SettlementPreparation.Unavailable(result.problem.toUiError(result.detail))
        }
    }

    override suspend fun settle(payment: RaylPayment): SettlementOutcome {
        PocketTimings.mark("payment confirmed")
        val outcome = try {
            pocket.pay(payment.quote)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            PocketPayOutcome.Unknown(cause.message)
        }
        return when (outcome) {
            is PocketPayOutcome.Paid ->
                SettlementOutcome.Paid(
                    feeMsats = outcome.feeSats * MSATS_PER_SAT,
                    preimage = outcome.preimage
                )

            is PocketPayOutcome.Failed ->
                SettlementOutcome.Failed(outcome.problem.toUiError(outcome.detail))

            is PocketPayOutcome.Unknown ->
                SettlementOutcome.Unknown(
                    PaymentUiError.Provider(RaylPaymentError.OutcomeUnknown(outcome.detail))
                )
        }
    }

    private fun PocketPaymentProblem.toUiError(detail: String?): PaymentUiError = when (this) {
        PocketPaymentProblem.Network -> PaymentUiError.NetworkUnavailable

        PocketPaymentProblem.InsufficientFunds ->
            PaymentUiError.Provider(RaylPaymentError.InsufficientFunds)

        PocketPaymentProblem.AlreadyPaid -> PaymentUiError.Provider(RaylPaymentError.AlreadyPaid)

        PocketPaymentProblem.Expired -> PaymentUiError.Provider(RaylPaymentError.InvoiceExpired)

        PocketPaymentProblem.BalanceSettling ->
            PaymentUiError.Provider(RaylPaymentError.BalanceSettling)

        // Unrecognized refusals keep Breez's own text.
        PocketPaymentProblem.Other -> PaymentUiError.Provider(RaylPaymentError.Rejected(detail))
    }

    private fun unavailable(error: RaylPaymentError) =
        SettlementPreparation.Unavailable(PaymentUiError.Provider(error))

    private companion object {
        const val MSATS_PER_SAT = 1_000L
    }
}
