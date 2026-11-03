package xyz.lilsus.rayl.providers.nwc.feature.payment

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
import xyz.lilsus.rayl.providers.nwc.integration.nwc.NwcPayOutcome
import xyz.lilsus.rayl.providers.nwc.integration.nwc.NwcPayments
import xyz.lilsus.raylsuite.feature.paymentflow.PreparedPayment
import xyz.lilsus.raylsuite.feature.paymentflow.SettlementOutcome

class NwcPaymentSettlementTest {
    @Test
    fun anErrorAfterSubmittingIsUnknownNeverFailed() = runTest {
        // PaymentSettlement contract: once submitted, a payment that ended without a verdict may
        // have paid. Reporting it as failed would invite the user to pay again (PR-04).
        val wallet = FakeWallet { throw IllegalStateException("relay closed after publish") }

        assertIs<SettlementOutcome.Unknown>(NwcPaymentSettlement(wallet).settle(payment()))
    }

    @Test
    fun cancellationDuringSettleIsPropagatedNotReported() = runTest {
        // A cancelled session must not record an outcome for a payment it no longer observes.
        val wallet = FakeWallet { throw CancellationException("session reset") }

        assertFailsWith<CancellationException> { NwcPaymentSettlement(wallet).settle(payment()) }
    }

    @Test
    fun eachWalletVerdictKeepsItsMeaning() = runTest {
        val cases = mapOf(
            NwcPayOutcome.Settled(preimage = "00", feesPaidMsats = 2_000) to
                SettlementOutcome.Paid::class,
            NwcPayOutcome.WalletRejected(code = "INSUFFICIENT_BALANCE", detail = null) to
                SettlementOutcome.Failed::class,
            NwcPayOutcome.DefinitelyNotSent(detail = "offline") to SettlementOutcome.Failed::class,
            NwcPayOutcome.Uncertain(detail = "no response") to SettlementOutcome.Unknown::class
        )

        cases.forEach { (verdict, expected) ->
            val outcome = NwcPaymentSettlement(FakeWallet { verdict }).settle(payment())
            assertEquals(expected, outcome::class, "$verdict")
        }
    }

    @Test
    fun aSettledPaymentReportsTheFeeAndPreimage() = runTest {
        val wallet = FakeWallet { NwcPayOutcome.Settled(preimage = "ab", feesPaidMsats = 2_000) }

        assertEquals(SettlementOutcome.Paid(2_000, "ab"), NwcPaymentSettlement(wallet).settle(payment()))
    }

    @Test
    fun aFeeTheWalletDoesNotReportStaysUnknown() = runTest {
        // NIP-47 makes fees_paid optional; a missing fee is not a fee of 0 (MOB-94).
        val wallet = FakeWallet { NwcPayOutcome.Settled(preimage = "ab", feesPaidMsats = null) }

        assertEquals(SettlementOutcome.Paid(null, "ab"), NwcPaymentSettlement(wallet).settle(payment()))
    }

    @Test
    fun theWalletIsAskedForTheEnteredAmountAndTheInvoicesHash() = runTest {
        // The hash is how a lost response is looked up later; the amount is what the user typed
        // for an amountless invoice.
        val wallet = FakeWallet { NwcPayOutcome.Settled(preimage = null, feesPaidMsats = null) }
        val payment = payment(amountOverrideMsats = 21_000)

        NwcPaymentSettlement(wallet).settle(payment)

        assertEquals(payment.invoice.write(), wallet.invoice)
        assertEquals(21_000L, wallet.amountMsats)
        assertEquals(payment.invoice.paymentHash.toHex(), wallet.paymentHash)
    }

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

private class FakeWallet(private val respond: () -> NwcPayOutcome) : NwcPayments {
    var invoice: String? = null
    var amountMsats: Long? = null
    var paymentHash: String? = null

    override suspend fun payInvoice(invoice: String, amountMsats: Long?, paymentHash: String): NwcPayOutcome {
        this.invoice = invoice
        this.amountMsats = amountMsats
        this.paymentHash = paymentHash
        return respond()
    }
}
