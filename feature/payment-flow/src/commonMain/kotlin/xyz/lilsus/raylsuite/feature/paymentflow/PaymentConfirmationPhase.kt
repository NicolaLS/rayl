package xyz.lilsus.raylsuite.feature.paymentflow

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentAmountQuote
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentCurrencyManager
import xyz.lilsus.raylsuite.feature.paymentsettings.PaymentConfirmationPolicy
import xyz.lilsus.raylsuite.feature.paymentui.PaymentConfirmationAmount

internal class PaymentConfirmationPhase<P : SettleablePayment>(
    private val settlement: PaymentSettlement<P>,
    private val currencyManager: PaymentCurrencyManager,
    private val confirmationPolicy: PaymentConfirmationPolicy,
    private val presentation: PaymentPresentationPhase,
    private val nowSeconds: () -> Long
) {
    private var awaitingConfirmation: AwaitingConfirmation<P>? = null

    suspend fun requestPayment(request: PreparedPayment): ConfirmationResult<P> {
        if (awaitingConfirmation != null) return ConfirmationResult.Presented
        // Amount entry can outlive the invoice that admission checked.
        if (rejectExpired(request)) return ConfirmationResult.Presented
        val amountMsats = request.amountOverrideMsats ?: request.invoice.amount?.msat
        if (request.paymentQuote != null && request.paymentQuote.amountMsats != amountMsats) {
            presentation.presentError(
                PaymentUiError.InvalidInvoice("Quoted amount does not match invoice")
            )
            return ConfirmationResult.Presented
        }
        presentation.showLoading(LoadingKind.Resolving)
        val prepared =
            when (val preparation = settlement.prepare(request)) {
                is SettlementPreparation.Ready -> preparation

                is SettlementPreparation.Unavailable -> {
                    presentation.presentError(preparation.error)
                    return ConfirmationResult.Presented
                }

                SettlementPreparation.AlreadyPaid -> return ConfirmationResult.AlreadyPaid(request)
            }
        currentCoroutineContext().ensureActive()
        val requiresConfirmation = !request.isManualEntry &&
            amountMsats != null &&
            (
                request.isFromHubWidget ||
                    confirmationPolicy.shouldConfirm(
                        amountMsats = amountMsats,
                        isManualEntry = false,
                        isPresetTarget = request.targetContext?.isPreset == true
                    )
                )
        if (!requiresConfirmation) return ConfirmationResult.Execute(prepared.payment)

        val display = confirmationAmount(amountMsats ?: 0L, request.paymentQuote)
        currentCoroutineContext().ensureActive()
        awaitingConfirmation = AwaitingConfirmation(prepared.payment, request)
        presentation.showConfirmation(
            amount = display,
            lnurlPayDisplay = request.lnurlPayDisplay,
            fundingSource = prepared.fundingSource
        )
        return ConfirmationResult.Presented
    }

    fun submit(): ConfirmationResult<P> {
        val awaiting = takeConfirmation() ?: return ConfirmationResult.Presented
        // The confirmation can stay open past the invoice's expiry. Breez still moves the funds
        // into a pending send before refusing an expired invoice (MOB-113), so it must never
        // reach the wallet.
        if (rejectExpired(awaiting.request)) return ConfirmationResult.Presented
        return ConfirmationResult.Execute(awaiting.payment)
    }

    fun dismiss(): Boolean = takeConfirmation() != null

    fun reset() {
        awaitingConfirmation = null
    }

    private fun takeConfirmation(): AwaitingConfirmation<P>? =
        awaitingConfirmation.also { awaitingConfirmation = null }

    private fun rejectExpired(request: PreparedPayment): Boolean {
        if (!request.invoice.isExpired(nowSeconds())) return false
        presentation.presentError(PaymentUiError.InvalidInvoice("Invoice has expired"))
        return true
    }

    private class AwaitingConfirmation<P>(val payment: P, val request: PreparedPayment)

    private suspend fun confirmationAmount(
        amountMsats: Long,
        paymentQuote: PaymentAmountQuote?
    ): PaymentConfirmationAmount {
        val exactSats =
            DisplayAmount(amountMsats / MSATS_PER_SAT, DisplayCurrency.Satoshi)
        paymentQuote?.let { quote ->
            return PaymentConfirmationAmount(
                primary = quote.requestedAmount,
                exactSats =
                    exactSats.takeIf {
                        quote.requestedAmount.currency is DisplayCurrency.Fiat
                    }
            )
        }
        val preferredAmount = currencyManager.convertMsatsToFreshDisplay(amountMsats)
        currentCoroutineContext().ensureActive()
        return PaymentConfirmationAmount(
            primary = preferredAmount,
            exactSats = exactSats.takeIf { preferredAmount.currency is DisplayCurrency.Fiat },
            primaryIsEstimate = preferredAmount.currency is DisplayCurrency.Fiat
        )
    }

    private companion object {
        const val MSATS_PER_SAT = 1_000L
    }
}
