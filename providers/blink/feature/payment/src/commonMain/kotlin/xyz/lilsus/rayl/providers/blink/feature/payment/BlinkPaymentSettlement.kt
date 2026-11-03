package xyz.lilsus.rayl.providers.blink.feature.payment

import fr.acinq.lightning.payment.Bolt11Invoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkApiError
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkApiException
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkConnectionException
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkFundingWallet
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkPaymentAmount
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkPaymentOutcome
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkPaymentRequest
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkPayments
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkWalletCurrency
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.convertMsatsToDisplayAmount
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentAmountQuote
import xyz.lilsus.raylsuite.feature.paymentflow.HubTargetContext
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentSettlement
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentUiError
import xyz.lilsus.raylsuite.feature.paymentflow.PreparedPayment
import xyz.lilsus.raylsuite.feature.paymentflow.SettleablePayment
import xyz.lilsus.raylsuite.feature.paymentflow.SettlementOutcome
import xyz.lilsus.raylsuite.feature.paymentflow.SettlementPreparation
import xyz.lilsus.raylsuite.feature.paymentui.LnurlPayDisplay

/** Pays admitted invoices from the Blink balance selected in wallet settings. */
class BlinkPaymentSettlement(
    private val blinkWallet: BlinkPayments,
    private val bitcoinPriceProvider: BitcoinPriceProvider
) : PaymentSettlement<BlinkPayment> {
    override suspend fun prepare(request: PreparedPayment): SettlementPreparation<BlinkPayment> {
        val fundingWallet = try {
            blinkWallet.prepareFundingWallet()
        } catch (cause: CancellationException) {
            throw cause
        } catch (error: BlinkApiException) {
            return SettlementPreparation.Unavailable(error.error.toPaymentUiError())
        } catch (_: BlinkConnectionException) {
            return SettlementPreparation.Unavailable(
                BlinkApiError.MissingWalletConnection.toPaymentUiError()
            )
        }
        currentCoroutineContext().ensureActive()
        val amountOverrideMsats = request.amountOverrideMsats
        val fundingAmountCents =
            if (amountOverrideMsats != null && fundingWallet.currency == BlinkWalletCurrency.USD) {
                usdPaymentAmountCents(amountOverrideMsats, request.paymentQuote)
                    ?: return SettlementPreparation.Unavailable(
                        PaymentUiError.ExchangeRateUnavailable(USD_CODE)
                    )
            } else {
                null
            }
        return SettlementPreparation.Ready(
            payment = BlinkPayment(
                invoice = request.invoice,
                amountOverrideMsats = request.amountOverrideMsats,
                targetContext = request.targetContext,
                lnurlPayDisplay = request.lnurlPayDisplay,
                fundingWallet = fundingWallet,
                fundingAmountCents = fundingAmountCents
            ),
            fundingSource = fundingWallet.currency.fundingSourceText()
        )
    }

    override suspend fun settle(payment: BlinkPayment): SettlementOutcome {
        val outcome = try {
            blinkWallet.submitPayment(
                BlinkPaymentRequest(
                    invoice = payment.invoice.write(),
                    fundingWallet = payment.fundingWallet,
                    amount = payment.amountOverrideMsats?.let { amount ->
                        when (payment.fundingWallet.currency) {
                            BlinkWalletCurrency.BTC -> BlinkPaymentAmount.Bitcoin(amount)

                            BlinkWalletCurrency.USD -> BlinkPaymentAmount.Usd(
                                requireNotNull(payment.fundingAmountCents)
                            )
                        }
                    }
                )
            )
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Throwable) {
            currentCoroutineContext().ensureActive()
            return SettlementOutcome.Unknown(
                PaymentUiError.Provider(BlinkPaymentError.StatusUnknown)
            )
        }
        return when (outcome) {
            is BlinkPaymentOutcome.Paid ->
                SettlementOutcome.Paid(
                    feeMsats = outcome.feesPaidMsats,
                    preimage = outcome.preimageHex
                )

            BlinkPaymentOutcome.AlreadyPaid ->
                SettlementOutcome.Paid(feeMsats = 0L, preimage = null, wasAlreadyPaid = true)

            is BlinkPaymentOutcome.DefinitiveFailure ->
                SettlementOutcome.Failed(outcome.error.toPaymentUiError())

            is BlinkPaymentOutcome.StatusUnknown ->
                SettlementOutcome.Unknown(PaymentUiError.Provider(BlinkPaymentError.StatusUnknown))
        }
    }

    override fun errorFor(cause: Throwable): PaymentUiError = when (cause) {
        is BlinkApiException -> cause.error.toPaymentUiError()
        is BlinkConnectionException -> BlinkApiError.MissingWalletConnection.toPaymentUiError()
        else -> PaymentUiError.Unexpected(cause.message)
    }

    private suspend fun usdPaymentAmountCents(
        amountMsats: Long,
        paymentQuote: PaymentAmountQuote?
    ): Long? {
        val requestedAmount = paymentQuote?.requestedAmount
        val requestedCurrency = requestedAmount?.currency as? DisplayCurrency.Fiat
        if (
            requestedAmount != null &&
            requestedCurrency?.iso4217?.equals(USD_CODE, ignoreCase = true) == true
        ) {
            return requestedAmount.minor.takeIf { it > 0L }
        }

        val usdRate = bitcoinPriceProvider.pricePerBitcoin(USD_CODE)
        currentCoroutineContext().ensureActive()
        val validRate = usdRate?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return convertMsatsToDisplayAmount(
            msats = amountMsats,
            info = CurrencyCatalog.infoFor(USD_CODE),
            fiatPricePerBitcoin = validRate
        )?.minor?.takeIf { it > 0L }
    }

    private companion object {
        const val USD_CODE = "USD"
    }
}

/** An admitted invoice bound to the Blink balance that pays it. */
data class BlinkPayment(
    override val invoice: Bolt11Invoice,
    override val amountOverrideMsats: Long?,
    override val targetContext: HubTargetContext?,
    override val lnurlPayDisplay: LnurlPayDisplay?,
    val fundingWallet: BlinkFundingWallet,
    val fundingAmountCents: Long?
) : SettleablePayment

private fun BlinkApiError.toPaymentUiError(): PaymentUiError =
    PaymentUiError.Provider(BlinkPaymentError.Api(this))
