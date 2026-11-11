package xyz.lilsus.rayl

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.russhwolf.settings.Settings
import xyz.lilsus.rayl.shared.R
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.core.ui.platform.rememberRetainedInstance
import xyz.lilsus.raylsuite.feature.onboarding.AutoPaySettingsScreen
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingBenefit
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingBenefitIcon
import xyz.lilsus.raylsuite.feature.onboarding.WelcomeScreen

/** Rayl's onboarding before any wallet: the welcome, which says what Rayl does, and Auto Pay. */
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
        AppOnboardingStep.Welcome -> WelcomeScreen(
            title = stringResource(R.string.welcome_title),
            description = stringResource(R.string.welcome_body),
            benefits = benefits(),
            actionTitle = stringResource(R.string.get_started),
            onGetStarted = { onboarding.moveTo(AppOnboardingStep.AutoPay) }
        )

        AppOnboardingStep.AutoPay -> {
            val formatter = rememberAmountFormatter()
            AutoPaySettingsScreen(
                body = stringResource(R.string.onboarding_autopay_body),
                confirmationMode = state.confirmationMode,
                thresholdSats = state.thresholdSats,
                currencyEquivalent = state.thresholdCurrencyEquivalent?.let(formatter::format),
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
private fun benefits(): List<OnboardingBenefit> = listOf(
    OnboardingBenefit(
        icon = OnboardingBenefitIcon.Scan,
        title = stringResource(R.string.welcome_scan_title),
        body = stringResource(R.string.welcome_scan_body)
    ),
    OnboardingBenefit(
        icon = OnboardingBenefitIcon.AutoPay,
        title = stringResource(R.string.welcome_autopay_title),
        body = stringResource(R.string.welcome_autopay_body)
    ),
    OnboardingBenefit(
        icon = OnboardingBenefitIcon.Balance,
        title = stringResource(R.string.welcome_balance_title),
        body = stringResource(R.string.welcome_balance_body)
    )
)
