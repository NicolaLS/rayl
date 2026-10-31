package xyz.lilsus.rayl.providers.blink

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.lilsus.rayl.providers.blink.feature.onboarding.BlinkOnboardingStep

/** App-scoped progress survives interruption and removal of the provider connection. */
internal class BlinkOnboardingState(private val settings: Settings) {
    private val mutableStep = MutableStateFlow(
        BlinkOnboardingStep.entries.firstOrNull { it.name == settings.getStringOrNull(KEY_STEP) }
            ?: BlinkOnboardingStep.Connect
    )
    val step = mutableStep.asStateFlow()

    fun moveTo(step: BlinkOnboardingStep) {
        settings.putString(KEY_STEP, step.name)
        mutableStep.value = step
    }

    fun canEnterApp(walletConnected: Boolean): Boolean =
        walletConnected && step.value == BlinkOnboardingStep.Complete

    private companion object {
        const val KEY_STEP = "blink.onboarding.step"
    }
}
