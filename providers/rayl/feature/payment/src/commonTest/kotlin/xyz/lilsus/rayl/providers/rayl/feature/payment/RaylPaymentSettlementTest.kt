package xyz.lilsus.rayl.providers.rayl.feature.payment

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
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketPayOutcome
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketPaymentProblem
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketPayments
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketQuote
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketQuoteResult
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentUiError
import xyz.lilsus.raylsuite.feature.paymentflow.PreparedPayment
import xyz.lilsus.raylsuite.feature.paymentflow.SettlementOutcome
import xyz.lilsus.raylsuite.feature.paymentflow.SettlementPreparation

class RaylPaymentSettlementTest {
    @Test
    fun quotesAmountlessInvoicesInWholeSats() = runTest {
        val pocket = FakePocket(PocketQuoteResult.Quoted(Quote(feeSats = 3)))

        val prepared = RaylPaymentSettlement(pocket).prepare(payment(amountOverrideMsats = 21_000))

        assertIs<SettlementPreparation.Ready<RaylPayment>>(prepared)
        assertEquals(21L, pocket.quotedAmountSats)
    }

    @Test
    fun rejectsSubSatoshiAmountsBeforeQuoting() = runTest {
        val pocket = FakePocket(PocketQuoteResult.Quoted(Quote(feeSats = 0)))

        val prepared = RaylPaymentSettlement(pocket).prepare(payment(amountOverrideMsats = 1_500))

        assertEquals(RaylPaymentError.SubSatoshiAmount, providerError(prepared))
        assertEquals(0, pocket.quotes)
    }

    @Test
    fun reportsMissingSpendingPowerBeforeConfirmation() = runTest {
        val prepared = RaylPaymentSettlement(FakePocket(PocketQuoteResult.InsufficientFunds))
            .prepare(payment())

        assertEquals(RaylPaymentError.InsufficientFunds, providerError(prepared))
    }

    @Test
    fun mapsPaymentOutcomes() = runTest {
        val quote = Quote(feeSats = 2)
        val pocket = FakePocket(PocketQuoteResult.Quoted(quote))
        val settlement = RaylPaymentSettlement(pocket)
        val ready = settlement.prepare(payment()) as SettlementPreparation.Ready<RaylPayment>

        pocket.outcome = PocketPayOutcome.Paid(feeSats = 2, preimage = "00")
        assertEquals(SettlementOutcome.Paid(2_000, "00"), settlement.settle(ready.payment))

        pocket.outcome = PocketPayOutcome.Unknown("pending")
        assertIs<SettlementOutcome.Unknown>(settlement.settle(ready.payment))

        pocket.outcome = PocketPayOutcome.Failed(PocketPaymentProblem.Other, null)
        assertIs<SettlementOutcome.Failed>(settlement.settle(ready.payment))
    }

    @Test
    fun mapsRecognizedRefusalsToTheirOwnMessages() = runTest {
        val expired = RaylPaymentSettlement(FakePocket(PocketQuoteResult.Rejected(PocketPaymentProblem.Expired, "x")))
            .prepare(payment())
        assertEquals(RaylPaymentError.InvoiceExpired, providerError(expired))

        val offline = RaylPaymentSettlement(FakePocket(PocketQuoteResult.Rejected(PocketPaymentProblem.Network, "x")))
            .prepare(payment())
        assertEquals(PaymentUiError.NetworkUnavailable, (offline as SettlementPreparation.Unavailable).error)

        val quote = Quote(feeSats = 1)
        val pocket = FakePocket(PocketQuoteResult.Quoted(quote))
        val settlement = RaylPaymentSettlement(pocket)
        val ready = settlement.prepare(payment()) as SettlementPreparation.Ready<RaylPayment>
        pocket.outcome = PocketPayOutcome.Failed(PocketPaymentProblem.AlreadyPaid, "x")
        val failed = assertIs<SettlementOutcome.Failed>(settlement.settle(ready.payment))
        assertEquals(RaylPaymentError.AlreadyPaid, (failed.error as PaymentUiError.Provider).error)
    }

    private fun providerError(preparation: SettlementPreparation<RaylPayment>) =
        ((preparation as SettlementPreparation.Unavailable).error as PaymentUiError.Provider).error

    private fun payment(amountOverrideMsats: Long? = null) = PreparedPayment(
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
        amountOverrideMsats = amountOverrideMsats
    )
}

private class Quote(override val feeSats: Long) : PocketQuote

private class FakePocket(private val quoteResult: PocketQuoteResult) : PocketPayments {
    var quotes = 0
    var quotedAmountSats: Long? = null
    var outcome: PocketPayOutcome = PocketPayOutcome.Failed(PocketPaymentProblem.Other, null)

    override suspend fun quote(invoice: String, amountSats: Long?): PocketQuoteResult {
        quotes++
        quotedAmountSats = amountSats
        return quoteResult
    }

    override suspend fun pay(quote: PocketQuote): PocketPayOutcome = outcome
}
