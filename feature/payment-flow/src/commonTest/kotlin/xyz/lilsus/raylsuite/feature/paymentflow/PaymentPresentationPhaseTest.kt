package xyz.lilsus.raylsuite.feature.paymentflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentCurrencyManager

class PaymentPresentationPhaseTest {
    @Test
    fun dismissingAResultCannotDismissLoading() = runTest {
        val presentation = PaymentPresentationPhase(
            PaymentCurrencyManager(BitcoinPriceProvider { null }, backgroundScope)
        )
        presentation.showLoading()
        presentation.dismissResult()
        assertEquals(PaymentUiState.Loading(), presentation.uiState.value)
    }

    @Test
    fun resetClearsTransientPresentationWithoutRememberingOldTransactions() = runTest {
        val presentation =
            PaymentPresentationPhase(
                PaymentCurrencyManager(
                    bitcoinPriceProvider = BitcoinPriceProvider { null },
                    scope = backgroundScope
                )
            )
        presentation.updateSessionTransactionIds(setOf("first", "second"))
        presentation.requestTransactionDetailNavigation("first")
        presentation.showLoading()

        presentation.reset()

        assertEquals(PaymentUiState.Active, presentation.uiState.value)
        assertEquals(0, presentation.newSessionTransactionCount.value)
        assertNull(presentation.transactionDetailNavigationTarget.value)

        presentation.updateSessionTransactionIds(setOf("first", "second"))
        assertEquals(2, presentation.newSessionTransactionCount.value)
    }
}
