package xyz.lilsus.rayl.providers.blink.feature.payment

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.toByteVector32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkApiError
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkConnectionError
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkConnectionException
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkErrorType
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkFundingWallet
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkPaymentAmount
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkPaymentOutcome
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkPaymentRequest
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkPayments
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkWalletCurrency
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentAmountQuote
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentUiError
import xyz.lilsus.raylsuite.feature.paymentflow.PreparedPayment
import xyz.lilsus.raylsuite.feature.paymentflow.SettlementOutcome
import xyz.lilsus.raylsuite.feature.paymentflow.SettlementPreparation

class BlinkPaymentSettlementTest {
    @Test
    fun anErrorAfterSubmittingIsUnknownNeverFailed() = runTest {
        // PaymentSettlement contract: once submitted, a payment that ended without a verdict may
        // have paid. Reporting it as failed would invite the user to pay again (PR-04).
        val wallet = FakeWallet()
        val settlement = settlement(wallet)
        val payment = settlement.prepared()
        wallet.submit = { throw IllegalStateException("connection reset after submit") }

        assertIs<SettlementOutcome.Unknown>(settlement.settle(payment))
    }

    @Test
    fun cancellationDuringSettleIsPropagatedNotReported() = runTest {
        // A cancelled session must not record an outcome for a payment it no longer observes.
        val wallet = FakeWallet()
        val settlement = settlement(wallet)
        val payment = settlement.prepared()
        wallet.submit = { throw CancellationException("session reset") }

        assertFailsWith<CancellationException> { settlement.settle(payment) }
    }

    @Test
    fun eachBlinkVerdictKeepsItsMeaning() = runTest {
        val rejected = BlinkApiError.BlinkError(BlinkErrorType.InsufficientBalance)
        val cases = mapOf(
            BlinkPaymentOutcome.Paid(preimageHex = "ab", feesPaidMsats = 3_000) to
                SettlementOutcome.Paid(feeMsats = 3_000, preimage = "ab"),
            // A fee Blink doesn't return is unknown, not 0 (MOB-94).
            BlinkPaymentOutcome.Paid(preimageHex = "cd", feesPaidMsats = null) to
                SettlementOutcome.Paid(feeMsats = null, preimage = "cd"),
            BlinkPaymentOutcome.AlreadyPaid to
                SettlementOutcome.Paid(feeMsats = 0, preimage = null, wasAlreadyPaid = true),
            BlinkPaymentOutcome.DefinitiveFailure(rejected) to
                SettlementOutcome.Failed(PaymentUiError.Provider(BlinkPaymentError.Api(rejected))),
            BlinkPaymentOutcome.StatusUnknown(BlinkApiError.Timeout) to
                SettlementOutcome.Unknown(PaymentUiError.Provider(BlinkPaymentError.StatusUnknown))
        )

        cases.forEach { (verdict, expected) ->
            val wallet = FakeWallet()
            val settlement = settlement(wallet)
            val payment = settlement.prepared()
            wallet.submit = { verdict }
            assertEquals(expected, settlement.settle(payment), "$verdict")
        }
    }

    @Test
    fun withoutAConnectedWalletNothingIsPrepared() = runTest {
        val wallet = FakeWallet(
            funding = { throw BlinkConnectionException(BlinkConnectionError.MissingConnection) }
        )

        assertIs<SettlementPreparation.Unavailable>(settlement(wallet).prepare(payment()))
    }

    @Test
    fun aBitcoinBalancePaysTheEnteredAmountInMsats() = runTest {
        val wallet = FakeWallet()
        val settlement = settlement(wallet)

        settlement.settle(settlement.prepared(payment(amountOverrideMsats = 21_000)))

        assertEquals(BlinkPaymentAmount.Bitcoin(21_000), wallet.request?.amount)
    }

    @Test
    fun aUsdBalancePaysTheTypedDollarAmountExactly() = runTest {
        // $5.00 typed is $5.00 debited, not a round trip through sats and the rate.
        val wallet = FakeWallet(currency = BlinkWalletCurrency.USD)
        val typed = PaymentAmountQuote(
            requestedAmount = DisplayAmount(500, DisplayCurrency.Fiat("USD")),
            amountMsats = 8_333_000,
            exchangeRate = 60_000.0
        )

        // At today's rate the same sats would be $5.08.
        val settlement = settlement(wallet, usdRate = 61_000.0)
        settlement.settle(settlement.prepared(payment(amountOverrideMsats = 8_333_000, quote = typed)))

        assertEquals(BlinkPaymentAmount.Usd(500), wallet.request?.amount)
    }

    @Test
    fun aUsdBalanceConvertsASatsAmountAtTheCurrentRate() = runTest {
        val wallet = FakeWallet(currency = BlinkWalletCurrency.USD)

        // 10,000 sats at $60,000 per bitcoin = $6.00.
        val settlement = settlement(wallet, usdRate = 60_000.0)
        settlement.settle(settlement.prepared(payment(amountOverrideMsats = 10_000_000)))

        assertEquals(BlinkPaymentAmount.Usd(600), wallet.request?.amount)
    }

    @Test
    fun aUsdBalanceWithoutAValidRateRefusesInsteadOfGuessing() = runTest {
        for (rate in listOf(null, 0.0, Double.NaN)) {
            val wallet = FakeWallet(currency = BlinkWalletCurrency.USD)

            val prepared = settlement(wallet, usdRate = rate)
                .prepare(payment(amountOverrideMsats = 10_000_000))

            assertEquals(
                PaymentUiError.ExchangeRateUnavailable("USD"),
                (prepared as? SettlementPreparation.Unavailable)?.error,
                "rate $rate"
            )
        }
    }

    private fun settlement(wallet: FakeWallet, usdRate: Double? = 60_000.0) =
        BlinkPaymentSettlement(wallet, BitcoinPriceProvider { usdRate })

    private suspend fun BlinkPaymentSettlement.prepared(request: PreparedPayment = payment()): BlinkPayment =
        assertIs<SettlementPreparation.Ready<BlinkPayment>>(prepare(request)).payment

    private fun payment(amountOverrideMsats: Long? = null, quote: PaymentAmountQuote? = null) = PreparedPayment(
        invoice = Bolt11Invoice.create(
            chain = Chain.Regtest,
            amount = null,
            paymentHash = Crypto.sha256("payment".encodeToByteArray()).toByteVector32(),
            privateKey = PrivateKey(ByteArray(32) { 1 }),
            description = Either.Left("test"),
            minFinalCltvExpiryDelta = Bolt11Invoice.DEFAULT_MIN_FINAL_EXPIRY_DELTA,
            features = Features(
                Feature.VariableLengthOnion to FeatureSupport.Optional,
                Feature.PaymentSecret to FeatureSupport.Optional
            ),
            timestampSeconds = 1L
        ),
        amountOverrideMsats = amountOverrideMsats,
        paymentQuote = quote
    )
}

private class FakeWallet(
    currency: BlinkWalletCurrency = BlinkWalletCurrency.BTC,
    private val funding: () -> BlinkFundingWallet = { BlinkFundingWallet("wallet", currency) }
) : BlinkPayments {
    var submit: () -> BlinkPaymentOutcome = { BlinkPaymentOutcome.Paid(null, null) }
    var request: BlinkPaymentRequest? = null

    override fun prepareFundingWallet(): BlinkFundingWallet = funding()

    override suspend fun submitPayment(request: BlinkPaymentRequest): BlinkPaymentOutcome {
        this.request = request
        return submit()
    }
}
