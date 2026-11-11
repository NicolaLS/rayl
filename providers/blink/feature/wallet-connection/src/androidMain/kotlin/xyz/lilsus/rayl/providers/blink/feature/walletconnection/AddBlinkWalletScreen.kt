package xyz.lilsus.rayl.providers.blink.feature.walletconnection

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import xyz.lilsus.rayl.providers.blink.ui.blinkErrorMessageFor
import xyz.lilsus.raylsuite.core.ui.components.CredentialConnectionScreen
import xyz.lilsus.raylsuite.core.ui.components.CredentialInput

@Composable
fun AddBlinkWalletScreen(
    state: AddBlinkWalletUiState,
    onBack: (() -> Unit)?,
    onShowInstructions: () -> Unit,
    onApiKeyChange: (String) -> Unit,
    onPaste: () -> Unit,
    onSubmit: () -> Unit,
    privacyPolicyUrl: String?,
    termsUrl: String?,
    modifier: Modifier = Modifier
) {
    CredentialConnectionScreen(
        title = stringResource(R.string.add_blink_wallet_title),
        description = stringResource(
            R.string.add_blink_wallet_description,
            xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
        ),
        actionTitle = stringResource(R.string.add_blink_wallet_connect),
        canSubmit = state.canSubmit,
        isSubmitting = state.isSaving,
        onSubmit = onSubmit,
        onBack = onBack,
        onShowInstructions = onShowInstructions,
        privacyPolicyUrl = privacyPolicyUrl,
        termsUrl = termsUrl,
        termsTitle = stringResource(R.string.add_blink_wallet_terms),
        modifier = modifier.testTag(BlinkWalletConnectionTestTags.SCREEN),
        actionModifier = Modifier.testTag(BlinkWalletConnectionTestTags.CONNECT_BUTTON),
        instructionsTitle = stringResource(R.string.add_blink_wallet_instructions),
        // Blink also imports contacts, which the user must hear about before connecting.
        connectionNotice = stringResource(R.string.add_blink_wallet_connection_notice)
    ) {
        CredentialInput(
            value = state.apiKey,
            onValueChange = onApiKeyChange,
            label = stringResource(R.string.add_blink_wallet_api_key_label),
            placeholder = stringResource(R.string.add_blink_wallet_api_key_placeholder),
            onPaste = onPaste,
            onSubmit = { if (state.canSubmit) onSubmit() },
            enabled = !state.isSaving,
            fieldModifier = Modifier.testTag(BlinkWalletConnectionTestTags.API_KEY_FIELD)
        )
        state.error?.let {
            Text(
                text = blinkErrorMessageFor(it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

object BlinkWalletConnectionTestTags {
    const val SCREEN = "blink_wallet_screen"
    const val API_KEY_FIELD = "blink_wallet_api_key_field"
    const val CONNECT_BUTTON = "blink_wallet_connect_button"
}
