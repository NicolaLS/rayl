package xyz.lilsus.rayl.providers.rayl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.lilsus.rayl.providers.rayl.experience.R
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRow
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRowDefaults
import xyz.lilsus.raylsuite.feature.onboarding.AgreementScreen
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingScaffold

/** Rayl's wallet terms and passkey, on the shared onboarding screens the external wallets use. */
@Composable
internal fun RaylOnboarding(runtime: RaylRuntime, otherWallets: List<RaylWalletOption>) {
    val state = runtime.onboardingState
    val step by state.step.collectAsStateWithLifecycle()
    val hasAgreed by state.hasAgreed.collectAsStateWithLifecycle()
    val activity by state.activity.collectAsStateWithLifecycle()
    val working = activity == RaylSetupActivity.Working
    val message = when (activity) {
        RaylSetupActivity.Failed -> stringResource(R.string.rayl_passkey_failed)
        RaylSetupActivity.PasskeyWithoutPrf -> stringResource(R.string.rayl_passkey_without_prf)
        RaylSetupActivity.Idle, RaylSetupActivity.Working -> null
    }
    when (val current = step) {
        RaylOnboardingStep.Checking, RaylOnboardingStep.Complete -> RaylStatusScreen(message = null)

        // Without passkeys Rayl's wallet cannot exist: connecting a wallet is simply the next step.
        RaylOnboardingStep.Unavailable -> RaylWalletChoiceScreen(
            title = stringResource(R.string.rayl_connect_wallet),
            info = null,
            otherWallets = otherWallets,
            onUseWallet = { runtime.useWallet(it) },
            onBack = null
        )

        RaylOnboardingStep.Agreement -> AgreementScreen(
            body = stringResource(R.string.rayl_onboarding_agreement_body),
            hasAgreed = hasAgreed,
            onAgreementChanged = state::setAgreement,
            onContinue = state::accept,
            onBack = null,
            isWorking = working,
            message = message
        )

        is RaylOnboardingStep.Accounts -> RaylAccounts(
            step = current,
            working = working,
            message = message,
            onChoose = state::choose,
            onCreate = state::createAccount,
            onBack = state::back
        )
    }
}

/** The passkey already holds accounts, maybe from other apps: share one, or start Rayl's own. */
@Composable
private fun RaylAccounts(
    step: RaylOnboardingStep.Accounts,
    working: Boolean,
    message: String?,
    onChoose: (String) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit
) {
    RaylOnboardingPage(
        title = stringResource(R.string.rayl_accounts_title),
        body = stringResource(R.string.rayl_accounts_body),
        onBack = onBack.takeUnless { working }
    ) {
        step.accounts.forEach { account ->
            AppSettingsRow(
                title = account,
                testTag = "rayl_account_$account",
                onClick = { if (!working) onChoose(account) }
            ) { RaylChevron() }
        }
        if (step.canCreate) {
            AppSettingsRow(
                title = stringResource(R.string.rayl_accounts_new),
                icon = Icons.Filled.Add,
                testTag = "rayl_account_new",
                onClick = { if (!working) onCreate() }
            )
        }
        if (working) CircularProgressIndicator(modifier = Modifier.size(24.dp))
        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun RaylOnboardingPage(
    title: String,
    body: String,
    onBack: (() -> Unit)?,
    content: @Composable () -> Unit
) {
    OnboardingScaffold(onBack = onBack) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSettingsRowDefaults.RowSpacing)
        ) {
            Text(text = title, style = MaterialTheme.typography.headlineMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            content()
        }
    }
}

@Composable
private fun RaylChevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(AppSettingsRowDefaults.ChevronSize)
    )
}
