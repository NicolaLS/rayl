package xyz.lilsus.rayl.providers.rayl

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.lilsus.rayl.providers.rayl.experience.R
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.feature.onboarding.AgreementScreen
import xyz.lilsus.raylsuite.feature.onboarding.AutoPaySettingsScreen
import xyz.lilsus.raylsuite.feature.onboarding.FeaturesScreen
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingFeaturePage

/** Rayl's wallet onboarding, built from the shared onboarding screens the providers use. */
@Composable
internal fun RaylOnboarding(runtime: RaylRuntime, step: RaylOnboardingStep) {
    val viewModel = runtime.onboarding
    val progress = runtime.onboardingState
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler(enabled = step != RaylOnboardingStep.Features, onBack = progress::back)

    when (step) {
        RaylOnboardingStep.Features -> FeaturesScreen(
            pages = raylFeaturePages(),
            currentPage = state.featuresPage,
            stepIndex = step.index(),
            totalSteps = RAYL_ONBOARDING_STEP_COUNT,
            onPageChanged = viewModel::setFeaturesPage,
            onContinue = { progress.moveTo(RaylOnboardingStep.AutoPay) },
            onBack = null
        )

        RaylOnboardingStep.AutoPay -> {
            val formatter = rememberAmountFormatter()
            AutoPaySettingsScreen(
                body = stringResource(R.string.rayl_onboarding_autopay_body),
                confirmationMode = state.confirmationMode,
                thresholdSats = state.thresholdSats,
                currencyEquivalent = state.thresholdCurrencyEquivalent?.let(formatter::format),
                stepIndex = step.index(),
                totalSteps = RAYL_ONBOARDING_STEP_COUNT,
                onConfirmationModeChanged = viewModel::setConfirmationMode,
                onThresholdChanged = viewModel::setThreshold,
                onContinue = { progress.moveTo(RaylOnboardingStep.Agreement) },
                onBack = progress::back
            )
        }

        RaylOnboardingStep.Agreement -> AgreementScreen(
            body = stringResource(R.string.rayl_onboarding_agreement_body),
            hasAgreed = state.hasAgreed,
            stepIndex = step.index(),
            totalSteps = RAYL_ONBOARDING_STEP_COUNT,
            onAgreementChanged = viewModel::setAgreement,
            onContinue = { if (state.hasAgreed) progress.moveTo(RaylOnboardingStep.Complete) },
            onBack = progress::back
        )

        RaylOnboardingStep.Complete -> Unit
    }
}

@Composable
private fun raylFeaturePages(): List<OnboardingFeaturePage> = listOf(
    OnboardingFeaturePage(
        title = stringResource(R.string.rayl_onboarding_page1_title),
        subtitle = stringResource(R.string.rayl_onboarding_page1_subtitle),
        body = stringResource(R.string.rayl_onboarding_page1_body)
    ),
    OnboardingFeaturePage(
        title = stringResource(R.string.rayl_onboarding_page2_title),
        subtitle = stringResource(R.string.rayl_onboarding_page2_subtitle),
        body = stringResource(R.string.rayl_onboarding_page2_body)
    ),
    OnboardingFeaturePage(
        title = stringResource(R.string.rayl_onboarding_page3_title),
        subtitle = stringResource(R.string.rayl_onboarding_page3_subtitle),
        body = stringResource(R.string.rayl_onboarding_page3_body)
    )
)
