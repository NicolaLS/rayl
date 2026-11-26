package xyz.lilsus.raylsuite.feature.paymentflow

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
        // When confirmation needs a fresh fiat display, fetch the exchange rate alongside wallet
        // preparation instead of waiting for preparation to finish first.
        val preparedAndDisplay = coroutineScope {
            val displayDeferred = if (requiresConfirmation && request.paymentQuote == null) {
                async { confirmationAmount(amountMsats ?: 0L, null) }
            } else {
                null
            }
            val preparation = try {
                settlement.prepare(request)
            } catch (error: Throwable) {
                displayDeferred?.cancel()
                throw error
            }
            when (preparation) {
                is SettlementPreparation.Ready -> {
                    currentCoroutineContext().ensureActive()
                    if (!requiresConfirmation) {
                        displayDeferred?.cancel()
                        return@coroutineScope ConfirmationResult.Execute(preparation.payment)
                    }
                    val display = displayDeferred?.await()
                        ?: confirmationAmount(amountMsats ?: 0L, request.paymentQuote)
                    currentCoroutineContext().ensureActive()
                    awaitingConfirmation = AwaitingConfirmation(preparation.payment, request)
                    presentation.showConfirmation(
                        amount = display,
                        lnurlPayDisplay = request.lnurlPayDisplay,
                        fundingSource = preparation.fundingSource
                    )
                    ConfirmationResult.Presented
                }

                is SettlementPreparation.Unavailable -> {
                    displayDeferred?.cancel()
                    presentation.presentError(preparation.error)
                    ConfirmationResult.Presented
                }

                SettlementPreparation.AlreadyPaid -> {
                    displayDeferred?.cancel()
                    ConfirmationResult.AlreadyPaid(request)
                }
            }
        }
        return preparedAndDisplay
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
