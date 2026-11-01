package xyz.lilsus.rayl.providers.nwc

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NwcOnboardingStateTest {
    @Test
    fun completionSurvivesRestartAndWalletRemoval() {
        // Regression test for MOB-1: onboarding completion was not persisted.
        val settings = MapSettings()
        NwcOnboardingState(settings).complete()

        assertTrue(NwcOnboardingState(settings).completed.value)
    }

    @Test
    fun interruptedOnboardingRemainsIncompleteAcrossRestart() {
        val settings = MapSettings()
        NwcOnboardingState(settings)

        assertFalse(NwcOnboardingState(settings).completed.value)
    }
}
