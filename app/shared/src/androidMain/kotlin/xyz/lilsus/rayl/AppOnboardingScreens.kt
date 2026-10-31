package xyz.lilsus.rayl

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.russhwolf.settings.Settings
import xyz.lilsus.rayl.shared.R
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.core.ui.platform.rememberRetainedInstance
import xyz.lilsus.raylsuite.feature.onboarding.AutoPaySettingsScreen
import xyz.lilsus.raylsuite.feature.onboarding.FeaturesScreen
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingFeaturePage
import xyz.lilsus.raylsuite.feature.onboarding.WelcomeScreen

/** Rayl's onboarding before any wallet: the welcome, what Rayl does, and Auto Pay. */
@Composable
internal fun AppOnboarding(onboarding: RaylAppOnboarding, settings: Settings) {
    val step by onboarding.step.collectAsStateWithLifecycle()
    val model = rememberRetainedInstance(
        key = "rayl-app-onboarding",
        factory = { AppOnboardingSettings(settings) },
        onDispose = AppOnboardingSettings::clear,
        releaseOnLeave = true
    )
    val viewModel = model.viewModel
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler(enabled = step != AppOnboardingStep.Welcome, onBack = onboarding::back)

    when (step) {
        AppOnboardingStep.Welcome -> Surface(modifier = Modifier.fillMaxSize()) {
            WelcomeScreen(
                title = stringResource(R.string.welcome_title),
                subtitle = "",
                description = stringResource(R.string.welcome_body),
                actionTitle = stringResource(R.string.get_started),
                onGetStarted = { onboarding.moveTo(AppOnboardingStep.Features) }
            )
        }

        AppOnboardingStep.Features -> FeaturesScreen(
            pages = featurePages(),
            currentPage = state.featuresPage,
            stepIndex = step.progressIndex(),
            totalSteps = APP_ONBOARDING_PROGRESS_STEPS,
            onPageChanged = viewModel::setFeaturesPage,
            onContinue = { onboarding.moveTo(AppOnboardingStep.AutoPay) },
            onBack = onboarding::back
        )

        AppOnboardingStep.AutoPay -> {
            val formatter = rememberAmountFormatter()
            AutoPaySettingsScreen(
                body = stringResource(R.string.onboarding_autopay_body),
                confirmationMode = state.confirmationMode,
                thresholdSats = state.thresholdSats,
                currencyEquivalent = state.thresholdCurrencyEquivalent?.let(formatter::format),
                stepIndex = step.progressIndex(),
                totalSteps = APP_ONBOARDING_PROGRESS_STEPS,
                onConfirmationModeChanged = viewModel::setConfirmationMode,
                onThresholdChanged = viewModel::setThreshold,
                onContinue = { onboarding.moveTo(AppOnboardingStep.Complete) },
                onBack = onboarding::back
            )
        }

        AppOnboardingStep.Complete -> Unit
    }
}

@Composable
private fun featurePages(): List<OnboardingFeaturePage> = listOf(
    OnboardingFeaturePage(
        title = stringResource(R.string.onboarding_page1_title),
        subtitle = stringResource(R.string.onboarding_page1_subtitle),
        body = stringResource(R.string.onboarding_page1_body)
    ),
    OnboardingFeaturePage(
        title = stringResource(R.string.onboarding_page2_title),
        subtitle = stringResource(R.string.onboarding_page2_subtitle),
        body = stringResource(R.string.onboarding_page2_body)
    ),
    OnboardingFeaturePage(
        title = stringResource(R.string.onboarding_page3_title),
        subtitle = stringResource(R.string.onboarding_page3_subtitle),
        body = stringResource(R.string.onboarding_page3_body)
    )
)
