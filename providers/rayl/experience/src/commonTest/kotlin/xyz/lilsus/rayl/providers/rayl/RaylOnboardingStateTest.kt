package xyz.lilsus.rayl.providers.rayl

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class RaylOnboardingStateTest {
    @Test
    fun anInterruptedOnboardingStartsOver() {
        val settings = MapSettings()
        RaylOnboardingState(settings).moveTo(RaylOnboardingStep.Agreement)
        assertEquals(RaylOnboardingStep.Features, RaylOnboardingState(settings).step.value)
    }

    @Test
    fun completingTheAgreementIsRemembered() {
        val settings = MapSettings()
        val state = RaylOnboardingState(settings)
        state.moveTo(RaylOnboardingStep.Complete)
        state.moveTo(RaylOnboardingStep.Features)
        assertEquals(RaylOnboardingStep.Complete, state.step.value)
        assertEquals(RaylOnboardingStep.Complete, RaylOnboardingState(settings).step.value)
    }

    @Test
    fun backGoesToThePreviousStep() {
        val state = RaylOnboardingState(MapSettings())
        state.moveTo(RaylOnboardingStep.Agreement)
        state.back()
        assertEquals(RaylOnboardingStep.AutoPay, state.step.value)
        state.back()
        state.back()
        assertEquals(RaylOnboardingStep.Features, state.step.value)
    }
}
