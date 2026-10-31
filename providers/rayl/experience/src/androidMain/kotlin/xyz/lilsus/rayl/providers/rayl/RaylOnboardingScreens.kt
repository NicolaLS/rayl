package xyz.lilsus.rayl.providers.rayl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.lilsus.rayl.providers.rayl.experience.R
import xyz.lilsus.raylsuite.feature.onboarding.AgreementScreen

/** Rayl's wallet terms, on the shared agreement screen the external wallets use too. */
@Composable
internal fun RaylOnboarding(runtime: RaylRuntime) {
    val state = runtime.onboardingState
    val hasAgreed by state.hasAgreed.collectAsStateWithLifecycle()
    AgreementScreen(
        body = stringResource(R.string.rayl_onboarding_agreement_body),
        hasAgreed = hasAgreed,
        stepIndex = 0,
        totalSteps = 0,
        onAgreementChanged = state::setAgreement,
        onContinue = state::accept,
        onBack = null
    )
}
