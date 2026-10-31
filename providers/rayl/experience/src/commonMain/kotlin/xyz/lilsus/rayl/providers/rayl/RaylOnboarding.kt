package xyz.lilsus.rayl.providers.rayl

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What Rayl's wallet needs before its first use: accepting its terms. Rayl's app onboarding
 * covers everything that is not specific to a wallet.
 *
 * TODO(passkey): The agreement (`rayl_onboarding_agreement_body`) says the balance is not backed
 * up, because the pocket key is device-only for now. Reword it once the key comes from a passkey,
 * see the production TODO on `PocketKey`.
 */
internal enum class RaylOnboardingStep { Agreement, Complete }

internal class RaylOnboardingState(private val settings: Settings) {
    private val mutableStep = MutableStateFlow(
        if (settings.getBoolean(KEY_COMPLETE, false)) {
            RaylOnboardingStep.Complete
        } else {
            RaylOnboardingStep.Agreement
        }
    )
    val step = mutableStep.asStateFlow()

    private val mutableHasAgreed = MutableStateFlow(false)
    val hasAgreed = mutableHasAgreed.asStateFlow()

    fun setAgreement(agreed: Boolean) {
        mutableHasAgreed.value = agreed
    }

    fun accept() {
        if (!mutableHasAgreed.value || mutableStep.value == RaylOnboardingStep.Complete) return
        settings.putBoolean(KEY_COMPLETE, true)
        mutableStep.value = RaylOnboardingStep.Complete
    }

    private companion object {
        const val KEY_COMPLETE = "rayl.pocket.onboarding.complete"
    }
}
