package xyz.lilsus.rayl.providers.nwc.integration.nwc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

/** What Rayl reports after `pay_invoice` got no clear answer and it looks the payment up. */
class NwcPaymentOutcomeTest {
    @Test
    fun aPaymentTheWalletReportsPendingForeverEndsAsUnknownWhenTheBudgetRunsOut() = runTest {
        var lookups = 0

        val outcome = awaitNwcPaymentOutcome(budgetMs = 15_000) {
            lookups++
            NwcLookupOutcome.Pending
        }

        assertEquals(NwcPayOutcome.Uncertain(detail = null), outcome)
        assertEquals(15_000, currentTime)
        assertTrue(lookups > 1, "Looked up only $lookups times")
    }

    @Test
    fun aLookupThatHangsIsCutOffByTheBudget() = runTest {
        val outcome = awaitNwcPaymentOutcome(budgetMs = 15_000) { awaitCancellation() }

        assertEquals(NwcPayOutcome.Uncertain(detail = null), outcome)
        assertEquals(15_000, currentTime)
    }

    @Test
    fun aPaymentThatSettlesWithinTheBudgetIsReportedPaid() = runTest {
        val answers = ArrayDeque(
            listOf(
                NwcLookupOutcome.Pending,
                NwcLookupOutcome.Pending,
                NwcLookupOutcome.Settled(preimage = "ab", feesPaidMsats = 2_000)
            )
        )

        val outcome = awaitNwcPaymentOutcome(budgetMs = 15_000) { answers.removeFirst() }

        assertEquals(NwcPayOutcome.Settled(preimage = "ab", feesPaidMsats = 2_000), outcome)
    }

    @Test
    fun aPaymentThatFailsAfterBeingPendingIsReportedRejected() = runTest {
        val answers = ArrayDeque(listOf(NwcLookupOutcome.Pending, NwcLookupOutcome.Failed))

        val outcome = awaitNwcPaymentOutcome(budgetMs = 15_000) { answers.removeFirst() }

        assertIs<NwcPayOutcome.WalletRejected>(outcome)
    }

    @Test
    fun noBudgetLeftMeansUnknownWithoutALookup() = runTest {
        val outcome = awaitNwcPaymentOutcome(budgetMs = -1) { error("Looked up") }

        assertEquals(NwcPayOutcome.Uncertain(detail = null), outcome)
    }
}
