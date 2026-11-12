package xyz.lilsus.raylsuite.feature.walletmanagement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency

class WalletBalanceTest {
    @Test
    fun aFailedRefreshKeepsTheLastBalance() = runTest {
        var reply: () -> DisplayAmount? = { DisplayAmount(2_100, DisplayCurrency.Satoshi) }
        val balance = WalletBalance(unconfined()) { reply() }
        assertNull(balance.amount.value)

        balance.refresh()
        assertEquals(DisplayAmount(2_100, DisplayCurrency.Satoshi), balance.amount.value)

        // Offline, or a wallet that does not tell: the row keeps showing what it knew.
        reply = { error("offline") }
        balance.refresh()
        reply = { null }
        balance.refresh()
        assertEquals(DisplayAmount(2_100, DisplayCurrency.Satoshi), balance.amount.value)

        reply = { DisplayAmount(1_234, DisplayCurrency.Fiat("USD")) }
        balance.refresh()
        assertEquals(DisplayAmount(1_234, DisplayCurrency.Fiat("USD")), balance.amount.value)
    }

    private fun TestScope.unconfined() = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
}
