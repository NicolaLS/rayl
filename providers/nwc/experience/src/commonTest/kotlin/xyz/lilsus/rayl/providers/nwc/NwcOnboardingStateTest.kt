package xyz.lilsus.rayl.providers.nwc

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NwcOnboardingStateTest {
    @Test
    fun interruptedOnboardingRemainsIncompleteAcrossRestart() {
        val settings = MapSettings()
        NwcOnboardingState(settings)

        assertFalse(NwcOnboardingState(settings).completed.value)
    }
}
