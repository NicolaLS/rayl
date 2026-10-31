package xyz.lilsus.rayl.providers.rayl

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class RaylOnboardingStateTest {
    @Test
    fun theTermsMustBeAcceptedToContinue() {
        val state = RaylOnboardingState(MapSettings())
        state.accept()
        assertEquals(RaylOnboardingStep.Agreement, state.step.value)
        state.setAgreement(true)
        state.accept()
        assertEquals(RaylOnboardingStep.Complete, state.step.value)
    }

    @Test
    fun acceptanceIsRemembered() {
        val settings = MapSettings()
        RaylOnboardingState(settings).apply {
            setAgreement(true)
            accept()
        }
        assertEquals(RaylOnboardingStep.Complete, RaylOnboardingState(settings).step.value)
    }
}
