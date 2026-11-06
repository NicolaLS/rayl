package xyz.lilsus.raylsuite.feature.currencysettings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Rayl 1.1 is the first public release: later versions must read the display currency it stored
 * (test project 2, item 6). Unread, amounts would show and be entered in another currency. If
 * this fails, the format changed: read the old one too.
 */
class StoredCurrencyFrom11Test {
    @Test
    fun readsTheCurrency11Stored() = runTest {
        val settings = MapSettings("currency.code" to "EUR")

        assertEquals("EUR", DefaultCurrencyPreferences(settings).currentCode())
    }
}
