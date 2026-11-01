package xyz.lilsus.rayl.providers.rayl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketReceipt

class RaylFundingTest {
    private val receipts = MutableSharedFlow<PocketReceipt>()
    private var invoices = 0

    @Test
    fun anInvoiceIsFirstOfferedToAnotherWalletApp() = runTest {
        val funding = funding()
        funding.request()
        assertEquals(
            FundingState.Awaiting("lnbcrt1", FundingPresentation.OpenWallet),
            funding.state.value
        )
        funding.openedInWallet()
        assertEquals(
            FundingState.Awaiting("lnbcrt1", FundingPresentation.InWallet),
            funding.state.value
        )
    }

    @Test
    fun theInvoiceIsShownWhenNoOtherWalletCanOpenIt() = runTest {
        val funding = funding()
        funding.request()
        funding.showInvoice()
        assertEquals(
            FundingState.Awaiting("lnbcrt1", FundingPresentation.Shown),
            funding.state.value
        )
    }

    @Test
    fun aPaymentToTheInvoiceReportsTheAmountThePayerChose() = runTest {
        val funding = funding()
        funding.request()
        receipts.emit(PocketReceipt("lnbcrt0", 500))
        assertTrue(funding.state.value is FundingState.Awaiting)
        receipts.emit(PocketReceipt("lnbcrt1", 2_100))
        assertEquals(FundingState.Received(2_100), funding.state.value)
    }

    @Test
    fun eachRequestCreatesANewInvoice() = runTest {
        val funding = funding()
        funding.request()
        funding.openedInWallet()
        funding.request()
        assertEquals(
            FundingState.Awaiting("lnbcrt2", FundingPresentation.OpenWallet),
            funding.state.value
        )
    }

    @Test
    fun aFailedInvoiceCanBeRetried() = runTest {
        var fail = true
        val funding = RaylFunding(
            createInvoice = { if (fail) error("offline") else "lnbcrt1" },
            receipts = receipts,
            description = "Rayl",
            scope = unconfined()
        )
        funding.request()
        assertEquals(FundingState.Failed, funding.state.value)
        fail = false
        funding.request()
        assertTrue(funding.state.value is FundingState.Awaiting)
    }

    private fun TestScope.funding() = RaylFunding(
        createInvoice = { "lnbcrt${++invoices}" },
        receipts = receipts,
        description = "Rayl",
        scope = unconfined()
    )

    private fun TestScope.unconfined() = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
}
