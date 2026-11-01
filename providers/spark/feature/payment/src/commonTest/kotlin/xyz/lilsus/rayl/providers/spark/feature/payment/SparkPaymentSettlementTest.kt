package xyz.lilsus.rayl.providers.spark.feature.payment

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
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPayOutcome
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPaymentClient
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPaymentProblem
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPayments
import xyz.lilsus.rayl.providers.spark.application.payment.SparkQuote
import xyz.lilsus.rayl.providers.spark.application.payment.SparkQuoteResult
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentUiError
import xyz.lilsus.raylsuite.feature.paymentflow.PreparedPayment
import xyz.lilsus.raylsuite.feature.paymentflow.SettlementOutcome
import xyz.lilsus.raylsuite.feature.paymentflow.SettlementPreparation

class SparkPaymentSettlementTest {
    @Test
    fun anErrorAfterSubmittingIsUnknownNeverFailed() = runTest {
        // PaymentSettlement contract: once submitted, a payment that ended without a verdict may
        // have paid. Reporting it as failed would invite the user to pay again (PR-04).
        val client = FakeClient(SparkQuoteResult.Quoted(Quote(feeSats = 1)))
        val settlement = settlement(client)
        val ready = settlement.prepare(payment()) as SettlementPreparation.Ready<SparkPayment>
        client.payFailure = IllegalStateException("connection reset after submit")

        assertIs<SettlementOutcome.Unknown>(settlement.settle(ready.payment))
    }

    @Test
    fun cancellationDuringSettleIsPropagatedNotReported() = runTest {
        // A cancelled session must not record an outcome for a payment it no longer observes.
        val client = FakeClient(SparkQuoteResult.Quoted(Quote(feeSats = 1)))
        val settlement = settlement(client)
        val ready = settlement.prepare(payment()) as SettlementPreparation.Ready<SparkPayment>
        client.payFailure = CancellationException("session reset")

        assertFailsWith<CancellationException> { settlement.settle(ready.payment) }
    }

    @Test
    fun quotesAmountlessInvoicesInWholeSats() = runTest {
        val client = FakeClient(SparkQuoteResult.Quoted(Quote(feeSats = 3)))

        val prepared = settlement(client).prepare(payment(amountOverrideMsats = 21_000))

        assertIs<SettlementPreparation.Ready<SparkPayment>>(prepared)
        assertEquals(21L, client.quotedAmountSats)
    }

    @Test
    fun rejectsSubSatoshiAmountsBeforeQuoting() = runTest {
        val client = FakeClient(SparkQuoteResult.Quoted(Quote(feeSats = 0)))

        val prepared = settlement(client).prepare(payment(amountOverrideMsats = 1_500))

        assertEquals(SparkPaymentError.SubSatoshiAmount, providerError(prepared))
        assertEquals(0, client.quotes)
    }

    @Test
    fun reportsMissingSpendingPowerBeforeConfirmation() = runTest {
        val prepared = settlement(FakeClient(SparkQuoteResult.InsufficientFunds))
            .prepare(payment())

        assertEquals(SparkPaymentError.InsufficientFunds, providerError(prepared))
    }

    @Test
    fun mapsPaymentOutcomes() = runTest {
        val quote = Quote(feeSats = 2)
        val client = FakeClient(SparkQuoteResult.Quoted(quote))
        val settlement = settlement(client)
        val ready = settlement.prepare(payment()) as SettlementPreparation.Ready<SparkPayment>

        client.outcome = SparkPayOutcome.Paid(feeSats = 2, preimage = "00")
        assertEquals(SettlementOutcome.Paid(2_000, "00"), settlement.settle(ready.payment))

        client.outcome = SparkPayOutcome.Unknown("pending")
        assertIs<SettlementOutcome.Unknown>(settlement.settle(ready.payment))

        client.outcome = SparkPayOutcome.Failed(SparkPaymentProblem.Other, null)
        assertIs<SettlementOutcome.Failed>(settlement.settle(ready.payment))
    }

    @Test
    fun mapsRecognizedRefusalsToTheirOwnMessages() = runTest {
        val expired = settlement(FakeClient(SparkQuoteResult.Rejected(SparkPaymentProblem.Expired, "x")))
            .prepare(payment())
        assertEquals(SparkPaymentError.InvoiceExpired, providerError(expired))

        val offline = settlement(FakeClient(SparkQuoteResult.Rejected(SparkPaymentProblem.Network, "x")))
            .prepare(payment())
        assertEquals(PaymentUiError.NetworkUnavailable, (offline as SettlementPreparation.Unavailable).error)

        val quote = Quote(feeSats = 1)
        val client = FakeClient(SparkQuoteResult.Quoted(quote))
        val settlement = settlement(client)
        val ready = settlement.prepare(payment()) as SettlementPreparation.Ready<SparkPayment>
        client.outcome = SparkPayOutcome.Failed(SparkPaymentProblem.AlreadyPaid, "x")
        val failed = assertIs<SettlementOutcome.Failed>(settlement.settle(ready.payment))
        assertEquals(SparkPaymentError.AlreadyPaid, (failed.error as PaymentUiError.Provider).error)
    }

    private suspend fun settlement(client: SparkPaymentClient) = SparkPaymentSettlement(SparkPayments().apply { attach(client) })

    @Test
    fun aDisconnectedWalletPaysNothing() = runTest {
        val prepared = SparkPaymentSettlement(SparkPayments()).prepare(payment())

        assertEquals(SparkPaymentError.WalletNotReady, providerError(prepared))
    }

    private fun providerError(preparation: SettlementPreparation<SparkPayment>) =
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

private class Quote(override val feeSats: Long) : SparkQuote

private class FakeClient(private val quoteResult: SparkQuoteResult) : SparkPaymentClient {
    var quotes = 0
    var quotedAmountSats: Long? = null
    var outcome: SparkPayOutcome = SparkPayOutcome.Failed(SparkPaymentProblem.Other, null)
    var payFailure: Throwable? = null

    override suspend fun quote(invoice: String, amountSats: Long?): SparkQuoteResult {
        quotes++
        quotedAmountSats = amountSats
        return quoteResult
    }

    override suspend fun pay(quote: SparkQuote): SparkPayOutcome {
        payFailure?.let { throw it }
        return outcome
    }
}
