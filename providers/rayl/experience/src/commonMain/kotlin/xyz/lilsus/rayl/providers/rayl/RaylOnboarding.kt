package xyz.lilsus.rayl.providers.rayl

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Introduction to Rayl's wallet before its first use: features, Auto Pay, and the agreement.
 *
 * TODO(passkey): The agreement (`rayl_onboarding_agreement_body`) says the balance is not backed
 * up, because the pocket key is device-only for now. Reword it once the key comes from a passkey,
 * see the production TODO on `PocketKey`.
 */
internal enum class RaylOnboardingStep { Features, AutoPay, Agreement, Complete }

/** Persists completion only; an interrupted onboarding starts over at its first step. */
internal class RaylOnboardingState(private val settings: Settings) {
    private val mutableStep = MutableStateFlow(
        if (settings.getBoolean(KEY_COMPLETE, false)) {
            RaylOnboardingStep.Complete
        } else {
            RaylOnboardingStep.Features
        }
    )
    val step = mutableStep.asStateFlow()

    fun moveTo(step: RaylOnboardingStep) {
        if (mutableStep.value == RaylOnboardingStep.Complete) return
        if (step == RaylOnboardingStep.Complete) settings.putBoolean(KEY_COMPLETE, true)
        mutableStep.value = step
    }

    fun back() {
        when (mutableStep.value) {
            RaylOnboardingStep.AutoPay -> moveTo(RaylOnboardingStep.Features)
            RaylOnboardingStep.Agreement -> moveTo(RaylOnboardingStep.AutoPay)
            else -> Unit
        }
    }

    private companion object {
        const val KEY_COMPLETE = "rayl.pocket.onboarding.complete"
    }
}

internal const val RAYL_ONBOARDING_STEP_COUNT = 3

internal fun RaylOnboardingStep.index(): Int = when (this) {
    RaylOnboardingStep.Features -> 0
    RaylOnboardingStep.AutoPay -> 1
    RaylOnboardingStep.Agreement, RaylOnboardingStep.Complete -> 2
}
