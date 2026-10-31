package xyz.lilsus.rayl.providers.rayl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.feature.onboarding.nativeOnboardingText

data class RaylNativeOnboardingSnapshot(
    val agreementTitle: String,
    val agreementBody: String,
    val agreementCheckboxTitle: String,
    val agreementNextTitle: String,
    val hasAgreed: Boolean
)

/** Rayl's wallet terms on iOS; Kotlin keeps the state, SwiftUI renders it. */
class RaylNativeOnboardingController internal constructor(private val runtime: RaylRuntime) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val state = runtime.onboardingState

    fun observe(onChange: (RaylNativeOnboardingSnapshot) -> Unit): () -> Unit {
        val job = scope.launch {
            combine(state.hasAgreed, runtime.languageRepository.preference) { agreed, _ ->
                val common = nativeOnboardingText()
                RaylNativeOnboardingSnapshot(
                    agreementTitle = common.agreementTitle,
                    agreementBody = text("rayl_onboarding_agreement_body"),
                    agreementCheckboxTitle = common.agreementCheckbox,
                    agreementNextTitle = common.agreementContinue,
                    hasAgreed = agreed
                )
            }.collect(onChange)
        }
        return { job.cancel() }
    }

    fun setAgreement(agreed: Boolean) = state.setAgreement(agreed)

    fun accept() = state.accept()

    fun clear() {
        scope.coroutineContext[Job]?.cancel()
    }
}
