package xyz.lilsus.raylsuite.core.ui.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency

/** Amounts as people read them in Rayl's languages (PR-01, PR-22, S6). */
class AmountFormatterTest {
    @Test
    fun everyCatalogCurrencyIsShownInItsOwnMinorUnits() {
        // The app stores fiat in the catalog's minor units; the platform formatter has its own
        // table. If they disagree for a currency, every amount in it is shown 100 times off.
        val formatter = currentAmountFormatter(AppLocale("en-US"))

        CurrencyCatalog.supported.filter { it.currency is DisplayCurrency.Fiat }.forEach { info ->
            val shown = formatter.format(DisplayAmount(123_456, info.currency))
            val expected = if (info.fractionDigits == 0) "123,456" else "1,234.56"
            assertTrue(expected in shown, "${info.code} shown as \"$shown\"")
        }
    }

    @Test
    fun germanAndSpanishUseTheirSeparatorsAndPutTheEuroSignAfter() {
        listOf("de-DE", "es-ES").forEach { tag ->
            val formatter = currentAmountFormatter(AppLocale(tag))

            val shown = formatter.format(DisplayAmount(1_234_567, DisplayCurrency.Fiat("EUR")))

            assertTrue("12.345,67" in shown, "$tag: \"$shown\"")
            assertEquals("€", formatter.affix(DisplayCurrency.Fiat("EUR")).suffix.trim(), tag)
            assertEquals("", formatter.affix(DisplayCurrency.Fiat("EUR")).prefix, tag)
        }
    }

    @Test
    fun englishPutsTheDollarSignFirst() {
        val formatter = currentAmountFormatter(AppLocale("en-US"))

        assertEquals("$12,345.67", formatter.format(DisplayAmount(1_234_567, DisplayCurrency.Fiat("USD"))))
        assertEquals(CurrencyAffix(prefix = "$", suffix = ""), formatter.affix(DisplayCurrency.Fiat("USD")))
    }

    @Test
    fun satsAreGroupedPerLanguageAndNeverShowDecimals() {
        assertEquals("21,000 sat", currentAmountFormatter(AppLocale("en-US")).format(sats(21_000)))
        assertEquals("21.000 sat", currentAmountFormatter(AppLocale("de-DE")).format(sats(21_000)))
    }

    private fun sats(amount: Long) = DisplayAmount(amount, DisplayCurrency.Satoshi)
}
