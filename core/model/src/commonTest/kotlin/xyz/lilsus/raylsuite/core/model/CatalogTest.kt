package xyz.lilsus.raylsuite.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogTest {
    @Test
    fun aStoredCurrencyNoLongerInTheCatalogFallsBackToSatoshis() {
        assertEquals(
            CurrencyCatalog.infoFor(CurrencyCatalog.DEFAULT_CODE),
            CurrencyCatalog.infoFor("unknown")
        )
    }
}
