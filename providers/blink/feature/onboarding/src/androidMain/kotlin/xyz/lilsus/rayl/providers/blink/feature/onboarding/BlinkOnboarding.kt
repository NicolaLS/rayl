package xyz.lilsus.rayl.providers.blink.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import xyz.lilsus.rayl.providers.blink.feature.walletconnection.AddBlinkWalletEvent
import xyz.lilsus.rayl.providers.blink.feature.walletconnection.AddBlinkWalletScreen
import xyz.lilsus.rayl.providers.blink.feature.walletconnection.AddBlinkWalletViewModel
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkWallet
import xyz.lilsus.raylsuite.core.ui.platform.CredentialClipboard
import xyz.lilsus.raylsuite.feature.onboarding.AgreementScreen
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingViewModel

@Composable
fun BlinkOnboarding(
    step: BlinkOnboardingStep,
    blinkWallet: BlinkWallet,
    onboardingViewModel: OnboardingViewModel,
    onCancel: () -> Unit,
    privacyPolicyUrl: String?,
    termsUrl: String?,
    onStepChanged: (BlinkOnboardingStep) -> Unit
) {
    val state by onboardingViewModel.uiState.collectAsStateWithLifecycle()
    when (step) {
        BlinkOnboardingStep.Connect -> AddWalletDestination(
            blinkWallet = blinkWallet,
            onCancel = onCancel,
            privacyPolicyUrl = privacyPolicyUrl,
            termsUrl = termsUrl
        )

        BlinkOnboardingStep.Agreement -> AgreementScreen(
            body = stringResource(
                R.string.blink_onboarding_agreement_body,
                xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
            ),
            hasAgreed = state.hasAgreed,
            onAgreementChanged = onboardingViewModel::setAgreement,
            onContinue = {
                if (state.hasAgreed) onStepChanged(BlinkOnboardingStep.Complete)
            },
            // The wallet is connected by now; Settings is where it is removed.
            onBack = null
        )

        BlinkOnboardingStep.Complete -> Unit
    }
}

@Composable
private fun AddWalletDestination(
    blinkWallet: BlinkWallet,
    onCancel: () -> Unit,
    privacyPolicyUrl: String?,
    termsUrl: String?
) {
    val viewModel = remember(blinkWallet) { AddBlinkWalletViewModel(blinkWallet) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val clipboard = remember(context, blinkWallet) { CredentialClipboard(context) }
    var showInstructions by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(viewModel, clipboard) {
        onDispose {
            // Connection state can advance the screen before Success is collected.
            if (blinkWallet.connection.value != null) clipboard.clearAfterSaving()
            clipboard.close()
            viewModel.clear()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                AddBlinkWalletEvent.Success -> clipboard.clearAfterSaving()
                AddBlinkWalletEvent.Cancelled -> clipboard.discard()
            }
        }
    }
    BackHandler(enabled = showInstructions) {
        showInstructions = false
    }

    if (showInstructions) {
        BlinkWalletInstructionsScreen(
            onBack = { showInstructions = false }
        )
    } else {
        AddBlinkWalletScreen(
            state = state,
            onBack = onCancel,
            privacyPolicyUrl = privacyPolicyUrl,
            termsUrl = termsUrl,
            onShowInstructions = {
                viewModel.reset()
                clipboard.discard()
                showInstructions = true
            },
            onApiKeyChange = {
                clipboard.retainFor(it)
                viewModel.updateApiKey(it)
            },
            onPaste = {
                clipboard.read()?.trim()?.takeIf(String::isNotEmpty)?.let(viewModel::updateApiKey)
            },
            onSubmit = viewModel::submit
        )
    }
}
