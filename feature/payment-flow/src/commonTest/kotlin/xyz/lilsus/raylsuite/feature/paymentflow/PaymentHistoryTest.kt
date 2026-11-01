package xyz.lilsus.raylsuite.feature.paymentflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentCurrencyManager

class PaymentHistoryTest {
    @Test
    fun onlyPaidInvoicesAreFoundForResubmissionChecks() = runTest {
        val history = history()
        history.record("paid", 5_000, result = paid)
        history.record("failed", 5_000, error = PaymentUiError.InvalidInvoice())

        assertNotNull(history.findPaidByPaymentHash("paid"))
        // A failed attempt must not block paying the same invoice again.
        assertNull(history.findPaidByPaymentHash("failed"))
        val failure = history.displayItems.value.first()
        assertEquals(TransactionStatus.Failure, failure.status)
        assertNull(failure.receipt)
    }

    @Test
    fun keepsOnlyTheTenMostRecentPaymentsNewestFirst() = runTest {
        val history = history()
        repeat(11) { history.record("paid-$it", 5_000, result = paid) }

        assertEquals(10, history.displayItems.value.size)
        assertNotNull(history.findPaidByPaymentHash("paid-10"))
        // Known limitation O-07: beyond ten payments, only the wallet can reject a re-scan.
        assertNull(history.findPaidByPaymentHash("paid-0"))
    }

    @Test
    fun resetForgetsEarlierPaymentsForTheNextSession() = runTest {
        val history = history()
        history.record("before-reset", 5_000, result = paid)

        history.reset()
        assertTrue(history.displayItems.value.isEmpty())
        history.record("after-reset", 5_000, result = paid)

        assertEquals(1, history.displayItems.value.size)
        assertNull(history.findPaidByPaymentHash("before-reset"))
        assertNotNull(history.findPaidByPaymentHash("after-reset"))
    }

    @Test
    fun currencyChangesKeepSatsAndReceiptDetails() = runTest {
        val currencyManager = PaymentCurrencyManager(
            BitcoinPriceProvider { 100_000.0 },
            backgroundScope
        )
        val usd = DisplayCurrency.Fiat("USD")
        currencyManager.setPreferredCurrency(usd)
        testScheduler.runCurrent()
        val history = PaymentHistory(currencyManager)
        val proof = "ab".repeat(32)
        history.record(
            paymentHash = "paid",
            amountMsats = 21_000,
            result = CompletedPayment(21_000, 1_000, preimage = proof),
            recipient = "recipient@example.com",
            domain = "example.com"
        )

        val original = assertNotNull(history.displayItems.value.single().receipt)
        assertEquals(DisplayAmount(2, usd), original.amount)
        assertEquals(DisplayAmount(1, usd), original.fee)
        assertEquals(DisplayAmount(21, DisplayCurrency.Satoshi), original.amountSats)
        assertEquals(DisplayAmount(1, DisplayCurrency.Satoshi), original.feeSats)
        assertEquals("recipient@example.com", original.recipient)
        assertEquals("example.com", original.domain)
        assertEquals(proof, original.preimage)

        currencyManager.setPreferredCurrency(DisplayCurrency.Satoshi)
        history.refreshDisplayItems()
        val refreshed = assertNotNull(history.displayItems.value.single().receipt)
        assertEquals(
            original.copy(amount = original.amountSats, fee = original.feeSats),
            refreshed
        )
    }

    @Test
    fun alreadyPaidWithoutOriginalSettlementDoesNotInventAReceipt() = runTest {
        val history = history()
        history.record(
            paymentHash = "already-paid",
            amountMsats = 21_000,
            result = CompletedPayment(0, 0, wasAlreadyPaid = true, preimage = null)
        )

        val transaction = assertNotNull(history.findPaidByPaymentHash("already-paid"))
        assertEquals(TransactionStatus.AlreadyPaid, transaction.status)
        assertNull(transaction.receipt)
        assertEquals(0L, assertNotNull(transaction.resultAmount).minor)
        assertTrue(assertIs<PaymentUiState.Success>(transaction.toDetailUiState()).wasAlreadyPaid)
    }

    private val paid = CompletedPayment(5_000, 1_000, preimage = null)

    private fun TestScope.history() = PaymentHistory(
        PaymentCurrencyManager(BitcoinPriceProvider { null }, backgroundScope)
    )
}
