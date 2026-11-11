package xyz.lilsus.raylsuite.feature.paymenthub.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HubLiveDataTest {
    @Test
    fun theDayChangeComparesThePriceWithTheOldestPoint() {
        val rate = HubRate("USD", price = 101.0, history = listOf(100.0, 90.0, 95.0))
        assertEquals(1.0, rate.changePercent!!, 1e-9)
        assertEquals(-50.0, HubRate("USD", 50.0, listOf(100.0, 80.0)).changePercent!!, 1e-9)
        // Without a chart there is nothing to compare with, so no change is claimed.
        assertNull(HubRate("USD", 101.0).changePercent)
    }

    @Test
    fun theChartEndsAtTheCurrentPriceAndFillsTheWholeHeight() {
        val chart = HubRate("USD", price = 120.0, history = listOf(100.0, 80.0, 110.0)).chart()
        assertEquals(listOf(0.5f, 0f, 0.75f, 1f), chart)
        // A flat day is a level line, not a division by zero.
        assertEquals(listOf(0.5f, 0.5f, 0.5f), HubRate("USD", 100.0, listOf(100.0, 100.0)).chart())
        assertEquals(emptyList(), HubRate("USD", 100.0, listOf(100.0)).chart())
    }

    @Test
    fun fiatConvertsToSatsAndBack() {
        val usd = HubRate("USD", price = 100_000.0)
        assertEquals(1_000.0, usd.satsPerUnit, 1e-9)
        assertEquals(HubConversion("10", "10000"), usd.convertFiat("10"))
        assertEquals(HubConversion("0.05", "50"), usd.convertFiat("0,05"))
        assertEquals(HubConversion("10.00", "10000"), usd.convertSats("10000"))
        assertEquals(HubConversion("0.01", "7"), usd.convertSats("7"))
    }

    @Test
    fun converterInputIsTidiedAndAnEmptyFieldClearsTheOther() {
        val usd = HubRate("USD", price = 100_000.0)
        assertEquals(HubConversion("12.34", "12340"), usd.convertFiat("12.345x"))
        assertEquals(HubConversion("", ""), usd.convertFiat(""))
        assertEquals(HubConversion("", ""), usd.convertSats("abc"))
        assertEquals(HubConversion("1.00", "1000"), usd.convertSats("1 000"))
    }

    @Test
    fun aCurrencyWithoutMinorUnitsConvertsWholeAmounts() {
        val yen = HubRate("JPY", price = 12_500_000.0)
        assertEquals(8.0, yen.satsPerUnit, 1e-9)
        assertEquals(HubConversion("1000", "8000"), yen.convertFiat("1000.5"))
        assertEquals(HubConversion("125", "1000"), yen.convertSats("1000"))
        // Eight sats per yen would hide most of the rate when rounded to a whole number.
        assertEquals(2, yen.satsFractionDigits())
        assertEquals(0, HubRate("USD", 100_000.0).satsFractionDigits())
    }

    @Test
    fun thePriceIsWrittenInTheCurrencysMinorUnits() {
        assertEquals(8_489_459L, HubRate("USD", 84_894.594).priceAmount().minor)
        assertEquals(12_500_000L, HubRate("JPY", 12_500_000.4).priceAmount().minor)
    }
}
