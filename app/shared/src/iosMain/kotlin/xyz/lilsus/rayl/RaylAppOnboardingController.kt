package xyz.lilsus.rayl

import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode
import xyz.lilsus.raylsuite.core.model.PaymentPreferences
import xyz.lilsus.raylsuite.core.ui.format.currentAmountFormatter
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingUiState
import xyz.lilsus.raylsuite.feature.onboarding.nativeOnboardingText
import xyz.lilsus.raylsuite.feature.onboarding.nativeOnboardingThresholdLabel

data class RaylAppOnboardingPage(val title: String, val subtitle: String, val body: String)

data class RaylAppOnboardingSnapshot(
    /** `welcome`, `features`, `autoPay`, or `complete`. */
    val step: String,
    val canGoBack: Boolean,
    val stepIndex: Int,
    val stepCount: Int,
    val backTitle: String,
    val welcomeTitle: String,
    val welcomeBody: String,
    val getStartedTitle: String,
    val featurePages: List<RaylAppOnboardingPage>,
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
    val thresholdStepCount: Int
)

/** Rayl's app onboarding on iOS; Kotlin keeps the steps and settings, SwiftUI renders them. */
class RaylAppOnboardingController internal constructor(
    private val onboarding: RaylAppOnboarding,
    private val settings: Settings,
    private val languageChanges: Flow<*>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var model: AppOnboardingSettings? = null

    private fun model(): AppOnboardingSettings =
        model ?: AppOnboardingSettings(settings).also { model = it }

    fun observe(onChange: (RaylAppOnboardingSnapshot) -> Unit): () -> Unit {
        val job = scope.launch {
            combine(onboarding.step, model().viewModel.uiState, languageChanges) { step, state, _ ->
                snapshot(step, state)
            }.collect(onChange)
        }
        return { job.cancel() }
    }

    fun getStarted() = onboarding.moveTo(AppOnboardingStep.Features)

    fun setFeaturePage(page: Int) = model().viewModel.setFeaturesPage(page)

    fun continueFeatures() = onboarding.moveTo(AppOnboardingStep.AutoPay)

    fun setConfirmationMode(value: String) = model().viewModel.setConfirmationMode(
        if (value == CONFIRMATION_ALWAYS) {
            PaymentConfirmationMode.Always
        } else {
            PaymentConfirmationMode.Above
        }
    )

    fun setThresholdIndex(index: Int) {
        PaymentPreferences.THRESHOLD_STEPS.getOrNull(index)?.let(model().viewModel::setThreshold)
    }

    fun continueAutoPay() {
        onboarding.moveTo(AppOnboardingStep.Complete)
    }

    fun back() = onboarding.back()

    private suspend fun snapshot(
        step: AppOnboardingStep,
        state: OnboardingUiState
    ): RaylAppOnboardingSnapshot {
        val common = nativeOnboardingText()
        val formatter = currentAmountFormatter()
        val sats = formatter.format(DisplayAmount(state.thresholdSats, DisplayCurrency.Satoshi))
        val thresholdAmount = state.thresholdCurrencyEquivalent
            ?.let(formatter::format)
            ?.let { "$sats ($it)" }
            ?: sats
        return RaylAppOnboardingSnapshot(
            step = when (step) {
                AppOnboardingStep.Welcome -> "welcome"
                AppOnboardingStep.Features -> "features"
                AppOnboardingStep.AutoPay -> "autoPay"
                AppOnboardingStep.Complete -> "complete"
            },
            canGoBack = step == AppOnboardingStep.Features || step == AppOnboardingStep.AutoPay,
            stepIndex = step.progressIndex(),
            stepCount = APP_ONBOARDING_PROGRESS_STEPS,
            backTitle = common.back,
            welcomeTitle = text("welcome_title"),
            welcomeBody = text("welcome_body"),
            getStartedTitle = text("get_started"),
            featurePages = (1..3).map { page ->
                RaylAppOnboardingPage(
                    title = text("onboarding_page${page}_title"),
                    subtitle = text("onboarding_page${page}_subtitle"),
                    body = text("onboarding_page${page}_body")
                )
            },
            featurePage = state.featuresPage,
            featuresNextTitle = common.featuresContinue,
            autoPayTitle = common.autoPayTitle,
            autoPayBody = text("onboarding_autopay_body"),
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
            thresholdStepCount = PaymentPreferences.THRESHOLD_STEPS.size
        )
    }

    private fun text(key: String) = nativeString(NativeStringResource("RaylApp", key))

    private companion object {
        const val CONFIRMATION_ALWAYS = "always"
        const val CONFIRMATION_ABOVE = "above"
    }
}
