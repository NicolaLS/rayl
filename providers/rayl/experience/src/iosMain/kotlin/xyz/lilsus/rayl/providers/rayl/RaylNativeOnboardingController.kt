package xyz.lilsus.rayl.providers.rayl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode
import xyz.lilsus.raylsuite.core.model.PaymentPreferences
import xyz.lilsus.raylsuite.core.ui.format.currentAmountFormatter
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingUiState
import xyz.lilsus.raylsuite.feature.onboarding.nativeOnboardingText
import xyz.lilsus.raylsuite.feature.onboarding.nativeOnboardingThresholdLabel

data class RaylNativeOnboardingPage(val title: String, val subtitle: String, val body: String)

data class RaylNativeOnboardingSnapshot(
    /** `features`, `autoPay`, `agreement`, or `complete`. */
    val step: String,
    val canGoBack: Boolean,
    val stepIndex: Int,
    val stepCount: Int,
    val backTitle: String,
    val featurePages: List<RaylNativeOnboardingPage>,
    val featurePage: Int,
    val featuresNextTitle: String,
    val autoPayTitle: String,
    val autoPayBody: String,
    val autoPayAlwaysTitle: String,
    val autoPayThresholdTitle: String,
    val autoPayThresholdLabel: String,
    val autoPayHint: String,
    val autoPayNextTitle: String,
    /** `always` or `above`. */
    val confirmationMode: String,
    val thresholdIndex: Int,
    val thresholdStepCount: Int,
    val agreementTitle: String,
    val agreementBody: String,
    val agreementCheckboxTitle: String,
    val agreementNextTitle: String,
    val hasAgreed: Boolean
)

/** Rayl's wallet onboarding on iOS; Kotlin keeps the steps and settings, SwiftUI renders them. */
class RaylNativeOnboardingController internal constructor(private val runtime: RaylRuntime) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val onboarding = runtime.onboarding
    private val progress = runtime.onboardingState

    fun observe(onChange: (RaylNativeOnboardingSnapshot) -> Unit): () -> Unit {
        val job = scope.launch {
            combine(
                onboarding.uiState,
                progress.step,
                runtime.languageRepository.preference
            ) { state, step, _ -> snapshot(state, step) }.collect(onChange)
        }
        return { job.cancel() }
    }

    fun setFeaturePage(page: Int) = onboarding.setFeaturesPage(page)

    fun continueFeatures() = progress.moveTo(RaylOnboardingStep.AutoPay)

    fun setConfirmationMode(value: String) = onboarding.setConfirmationMode(
        if (value == CONFIRMATION_ALWAYS) {
            PaymentConfirmationMode.Always
        } else {
            PaymentConfirmationMode.Above
        }
    )

    fun setThresholdIndex(index: Int) {
        PaymentPreferences.THRESHOLD_STEPS.getOrNull(index)?.let(onboarding::setThreshold)
    }

    fun continueAutoPay() = progress.moveTo(RaylOnboardingStep.Agreement)

    fun setAgreement(agreed: Boolean) = onboarding.setAgreement(agreed)

    fun continueAgreement() {
        if (onboarding.uiState.value.hasAgreed) progress.moveTo(RaylOnboardingStep.Complete)
    }

    fun back() = progress.back()

    fun clear() {
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun snapshot(
        state: OnboardingUiState,
        step: RaylOnboardingStep
    ): RaylNativeOnboardingSnapshot {
        val common = nativeOnboardingText()
        val formatter = currentAmountFormatter()
        val sats = formatter.format(DisplayAmount(state.thresholdSats, DisplayCurrency.Satoshi))
        val thresholdAmount = state.thresholdCurrencyEquivalent
            ?.let(formatter::format)
            ?.let { "$sats ($it)" }
            ?: sats
        return RaylNativeOnboardingSnapshot(
            step = when (step) {
                RaylOnboardingStep.Features -> "features"
                RaylOnboardingStep.AutoPay -> "autoPay"
                RaylOnboardingStep.Agreement -> "agreement"
                RaylOnboardingStep.Complete -> "complete"
            },
            canGoBack = step == RaylOnboardingStep.AutoPay || step == RaylOnboardingStep.Agreement,
            stepIndex = step.index(),
            stepCount = RAYL_ONBOARDING_STEP_COUNT,
            backTitle = common.back,
            featurePages = (1..3).map { page ->
                RaylNativeOnboardingPage(
                    title = text("rayl_onboarding_page${page}_title"),
                    subtitle = text("rayl_onboarding_page${page}_subtitle"),
                    body = text("rayl_onboarding_page${page}_body")
                )
            },
            featurePage = state.featuresPage,
            featuresNextTitle = common.featuresContinue,
            autoPayTitle = common.autoPayTitle,
            autoPayBody = text("rayl_onboarding_autopay_body"),
            autoPayAlwaysTitle = common.autoPayAlways,
            autoPayThresholdTitle = common.autoPayThreshold,
            autoPayThresholdLabel = nativeOnboardingThresholdLabel(thresholdAmount),
            autoPayHint = common.autoPayHint,
            autoPayNextTitle = common.autoPayContinue,
            confirmationMode = if (state.confirmationMode == PaymentConfirmationMode.Always) {
                CONFIRMATION_ALWAYS
            } else {
                CONFIRMATION_ABOVE
            },
            thresholdIndex = PaymentPreferences.thresholdToStepIndex(state.thresholdSats),
            thresholdStepCount = PaymentPreferences.THRESHOLD_STEPS.size,
            agreementTitle = common.agreementTitle,
            agreementBody = text("rayl_onboarding_agreement_body"),
            agreementCheckboxTitle = common.agreementCheckbox,
            agreementNextTitle = common.agreementContinue,
            hasAgreed = state.hasAgreed
        )
    }

    private companion object {
        const val CONFIRMATION_ALWAYS = "always"
        const val CONFIRMATION_ABOVE = "above"
    }
}
