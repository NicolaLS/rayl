package xyz.lilsus.raylsuite.feature.paymentcurrency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider

class PaymentCurrencyManagerTest {
    @Test
    fun paymentQuoteFetchesARateInsteadOfReusingTheDisplayedOne() = runTest {
        var rate = 50_000.0
        val manager =
            PaymentCurrencyManager(
                bitcoinPriceProvider = BitcoinPriceProvider { rate },
                scope = backgroundScope
            )
        manager.setPreferredCurrency(DisplayCurrency.Fiat("USD"))
        testScheduler.runCurrent()
        assertEquals(50_000.0, manager.state.value.exchangeRate)
        val enteredAmount = DisplayAmount(500, DisplayCurrency.Fiat("USD"))

        // The price moves after the display rate was fetched; the payment must use the new one.
        rate = 100_000.0
        val quote = manager.quote(enteredAmount)

        // $5.00 at $100,000 per bitcoin = 5,000 sats.
        assertEquals(enteredAmount, quote?.requestedAmount)
        assertEquals(5_000_000L, quote?.amountMsats)
        assertEquals(100_000.0, quote?.exchangeRate)
    }

    @Test
    fun fiatQuoteFailsClosedWithoutRate() = runTest {
        val manager =
            PaymentCurrencyManager(
                bitcoinPriceProvider = BitcoinPriceProvider { null },
                scope = backgroundScope
            )

        assertNull(manager.quote(DisplayAmount(500, DisplayCurrency.Fiat("USD"))))
    }

    @Test
    fun unknownFiatCurrencyIsNotTreatedAsSatoshis() = runTest {
        val manager =
            PaymentCurrencyManager(
                bitcoinPriceProvider = BitcoinPriceProvider { 1.0 },
                scope = backgroundScope
            )

        assertNull(manager.quote(DisplayAmount(500, DisplayCurrency.Fiat("XYZ"))))
    }
}
