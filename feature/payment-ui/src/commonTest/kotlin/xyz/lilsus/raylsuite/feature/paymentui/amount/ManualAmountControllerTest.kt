package xyz.lilsus.raylsuite.feature.paymentui.amount

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency

class ManualAmountControllerTest {
    @Test
    fun fiatStartsPrimaryAndSwitchesTheEnteredValueToWholeSats() {
        val controller = ManualAmountController(usdConfig())
        assertEquals(DisplayCurrency.Fiat("USD"), controller.current().currency)
        controller.presetAmount(DisplayAmount(100, DisplayCurrency.Fiat("USD")))
        assertEquals(DisplayAmount(1667, DisplayCurrency.Satoshi), controller.current().equivalentAmount)

        val sats = controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        assertEquals(DisplayAmount(1667, DisplayCurrency.Satoshi), controller.enteredAmount())
        assertFalse(sats.allowDecimal)
        assertEquals(DisplayCurrency.Fiat("USD"), sats.alternativeCurrency)

        val fiat = controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        assertEquals(DisplayAmount(100, DisplayCurrency.Fiat("USD")), fiat.amount)
        assertTrue(fiat.allowDecimal)
    }

    @Test
    fun roundTripPreservesExactSatsUntilTheUserEditsFiat() {
        val controller = ManualAmountController(usdConfig())
        controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        controller.handleKeyPress(ManualAmountKey.Digit(1))
        controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        assertEquals(DisplayAmount(1, DisplayCurrency.Fiat("USD")), controller.enteredAmount())
        controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        assertEquals(DisplayAmount(1, DisplayCurrency.Satoshi), controller.enteredAmount())

        controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        controller.handleKeyPress(ManualAmountKey.Backspace)
        controller.handleKeyPress(ManualAmountKey.Digit(2))
        controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        assertEquals(DisplayAmount(34, DisplayCurrency.Satoshi), controller.enteredAmount())
    }

    @Test
    fun rateRefreshKeepsSatEntryAndNewEntryRestoresFiat() {
        val controller = ManualAmountController(usdConfig())
        controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        controller.presetAmount(DisplayAmount(1000, DisplayCurrency.Satoshi))
        val refreshed = controller.reset(usdConfig().copy(exchangeRate = 100_000.0), clearInput = false)
        assertEquals(DisplayAmount(1000, DisplayCurrency.Satoshi), refreshed.amount)
        assertEquals(DisplayAmount(100, DisplayCurrency.Fiat("USD")), refreshed.equivalentAmount)
        val reset = controller.reset(usdConfig())
        assertEquals(DisplayCurrency.Fiat("USD"), reset.currency)
        assertNull(reset.amount)
    }

    @Test
    fun satoshiEntryWorksWithoutAnExchangeRate() {
        val controller = ManualAmountController(usdConfig().copy(exchangeRate = null))
        controller.handleKeyPress(ManualAmountKey.Digit(5))
        assertNull(controller.current().equivalentAmount)
        controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        assertNull(controller.enteredAmount())
        controller.handleKeyPress(ManualAmountKey.Digit(2))
        assertEquals(DisplayAmount(2, DisplayCurrency.Satoshi), controller.enteredAmount())
        assertEquals(RangeStatus.InRange, controller.current().rangeStatus)
        assertNull(controller.current().equivalentAmount)
    }

    @Test
    fun zeroDecimalFiatStaysIntegralInBothUnits() {
        val controller = ManualAmountController(ManualAmountConfig(CurrencyCatalog.infoFor("JPY"), 10_000_000.0))
        controller.handleKeyPress(ManualAmountKey.Digit(5))
        assertFalse(controller.current().allowDecimal)
        val sats = controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        assertEquals(DisplayAmount(50, DisplayCurrency.Satoshi), sats.amount)
        val yen = controller.handleKeyPress(ManualAmountKey.SwitchCurrency)
        assertEquals(DisplayAmount(5, DisplayCurrency.Fiat("JPY")), yen.amount)
        assertFalse(yen.allowDecimal)
    }

    @Test
    fun inclusiveSatoshiBoundsAcceptExactlyMinAndMax() {
        val controller = ManualAmountController(
            ManualAmountConfig(CurrencyCatalog.infoFor("SAT"), null, minMsats = 2000, maxMsats = 9000)
        )
        assertEquals(RangeStatus.Unknown, controller.current().rangeStatus)
        controller.handleKeyPress(ManualAmountKey.Digit(2))
        assertEquals(RangeStatus.InRange, controller.current().rangeStatus)
        controller.handleKeyPress(ManualAmountKey.Backspace)
        controller.handleKeyPress(ManualAmountKey.Digit(9))
        assertEquals(RangeStatus.InRange, controller.current().rangeStatus)
    }

    @Test
    fun zeroAndClearedEntryShowNoErrorButPositiveAmountBelowMinDoes() {
        val controller = ManualAmountController(
            ManualAmountConfig(CurrencyCatalog.infoFor("SAT"), null, minMsats = 2000)
        )
        assertEquals(RangeStatus.Unknown, controller.current().rangeStatus)
        controller.handleKeyPress(ManualAmountKey.Digit(0))
        assertEquals(RangeStatus.Unknown, controller.current().rangeStatus)
        assertNull(controller.enteredAmount())
        controller.handleKeyPress(ManualAmountKey.Digit(1))
        assertEquals(RangeStatus.BelowMin, controller.current().rangeStatus)
        controller.handleKeyPress(ManualAmountKey.Backspace)
        assertEquals(RangeStatus.Unknown, controller.current().rangeStatus)
        assertNull(controller.enteredAmount())
        assertEquals(RangeStatus.Unknown, controller.reset().rangeStatus)
    }

    @Test
    fun fiatRangeUsesRoundedPaymentAmount() {
        val controller = ManualAmountController(usdConfig().copy(maxMsats = 167_000))
        controller.handleKeyPress(ManualAmountKey.Decimal)
        controller.handleKeyPress(ManualAmountKey.Digit(1))
        assertEquals(RangeStatus.InRange, controller.current().rangeStatus)
        // $0.10 converts to 166,667 msat, but payment rounds upward to 167,000 msat.
        controller.reset(usdConfig().copy(maxMsats = 166_900), clearInput = false)
        assertEquals(RangeStatus.AboveMax, controller.current().rangeStatus)
    }

    private fun usdConfig() = ManualAmountConfig(CurrencyCatalog.infoFor("USD"), 60_000.0)
}
