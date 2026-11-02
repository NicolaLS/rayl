package xyz.lilsus.rayl.providers.nwc.feature.payment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import xyz.lilsus.rayl.providers.nwc.integration.nwc.NwcPayOutcome
import xyz.lilsus.rayl.providers.nwc.integration.nwc.NwcPayments
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentSettlement
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentUiError
import xyz.lilsus.raylsuite.feature.paymentflow.PreparedPayment
import xyz.lilsus.raylsuite.feature.paymentflow.SettlementOutcome
import xyz.lilsus.raylsuite.feature.paymentflow.SettlementPreparation

/** Pays admitted invoices through the connected Nostr Wallet Connect wallet. */
class NwcPaymentSettlement(private val nwcWallet: NwcPayments) :
    PaymentSettlement<PreparedPayment> {
    override suspend fun prepare(request: PreparedPayment): SettlementPreparation<PreparedPayment> =
        SettlementPreparation.Ready(request)

    override suspend fun settle(payment: PreparedPayment): SettlementOutcome {
        val outcome = try {
            nwcWallet.payInvoice(
                invoice = payment.invoice.write(),
                amountMsats = payment.amountOverrideMsats,
                paymentHash = payment.invoice.paymentHash.toHex()
            )
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Throwable) {
            currentCoroutineContext().ensureActive()
            NwcPayOutcome.Uncertain(null)
        }
        return when (outcome) {
            is NwcPayOutcome.Settled ->
                SettlementOutcome.Paid(
                    feeMsats = outcome.feesPaidMsats ?: 0L,
                    preimage = outcome.preimage
                )

            is NwcPayOutcome.WalletRejected ->
                SettlementOutcome.Failed(
                    PaymentUiError.Provider(NwcPaymentError.Rejected(outcome.code, outcome.detail))
                )

            is NwcPayOutcome.DefinitelyNotSent ->
                SettlementOutcome.Failed(
                    PaymentUiError.Provider(NwcPaymentError.DefinitelyNotSent(outcome.detail))
                )

            is NwcPayOutcome.Uncertain ->
                SettlementOutcome.Unknown(
                    PaymentUiError.Provider(NwcPaymentError.OutcomeUnknown(outcome.detail))
                )
        }
    }
}
