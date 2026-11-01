package xyz.lilsus.rayl

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaylAppOnboardingTest {
    @Test
    fun anInterruptedOnboardingStartsOverAtTheWelcome() {
        val settings = MapSettings()
        RaylAppOnboarding(settings).moveTo(AppOnboardingStep.AutoPay)
        assertEquals(AppOnboardingStep.Welcome, RaylAppOnboarding(settings).step.value)
        assertFalse(appOnboarded(settings))
    }

    @Test
    fun completionIsRememberedAndFinal() {
        val settings = MapSettings()
        val onboarding = RaylAppOnboarding(settings)
        onboarding.moveTo(AppOnboardingStep.Complete)
        onboarding.moveTo(AppOnboardingStep.Welcome)
        assertEquals(AppOnboardingStep.Complete, onboarding.step.value)
        assertTrue(appOnboarded(settings))
        assertEquals(AppOnboardingStep.Complete, RaylAppOnboarding(settings).step.value)
    }
}
