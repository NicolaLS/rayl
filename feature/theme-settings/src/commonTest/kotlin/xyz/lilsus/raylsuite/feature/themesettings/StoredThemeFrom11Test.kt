package xyz.lilsus.raylsuite.feature.themesettings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.model.ThemePreference

/**
 * Rayl 1.1 is the first public release: later versions must read the theme it stored (test
 * project 2, item 6). If this fails, the format changed: read the old one too.
 */
class StoredThemeFrom11Test {
    @Test
    fun readsTheTheme11Stored() = runTest {
        val stored11 = mapOf("light" to ThemePreference.Light, "dark" to ThemePreference.Dark)
        stored11.forEach { (stored, theme) ->
            val settings = MapSettings("appearance.selectedTheme" to stored)

            assertEquals(theme, DefaultThemePreferences(settings).current(), stored)
        }
        assertEquals(ThemePreference.System, DefaultThemePreferences(MapSettings()).current())
    }
}
