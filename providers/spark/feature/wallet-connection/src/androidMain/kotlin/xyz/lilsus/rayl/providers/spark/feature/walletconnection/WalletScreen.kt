package xyz.lilsus.rayl.providers.spark.feature.walletconnection

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.lilsus.rayl.providers.spark.application.wallet.CredentialProblemKind
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletAccessState
import xyz.lilsus.rayl.providers.spark.feature.walletconnection.R
import xyz.lilsus.raylsuite.core.ui.components.BackIconButton
import xyz.lilsus.raylsuite.core.ui.components.CredentialConnectionScreen
import xyz.lilsus.raylsuite.core.ui.components.CredentialInput
import xyz.lilsus.raylsuite.core.ui.platform.rememberCredentialClipboard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletConnectionScreen(
    state: WalletUiState,
    onBack: (() -> Unit)?,
    dispatch: (WalletAction) -> Unit,
    modifier: Modifier = Modifier,
    onShowInstructions: (() -> Unit)? = null,
    privacyPolicyUrl: String? = null,
    termsUrl: String? = null
) {
    val clipboard = rememberCredentialClipboard()
    val currentAccess by rememberUpdatedState(state.access)
    DisposableEffect(clipboard) {
        onDispose {
            if (currentAccess == WalletAccessState.Connected) clipboard.clearAfterSaving()
            clipboard.discard()
        }
    }
    LaunchedEffect(state.access) {
        if (state.access == WalletAccessState.Connected) clipboard.clearAfterSaving()
    }
    if (state.access == WalletAccessState.NoWallet ||
        state.access == WalletAccessState.Connecting
    ) {
        CredentialConnectionScreen(
            title = stringResource(R.string.spark_wallet_import_title),
            description = stringResource(
                R.string.spark_wallet_import_body,
                xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
            ),
            actionTitle = stringResource(R.string.spark_wallet_import_action),
            canSubmit =
                state.access == WalletAccessState.NoWallet && state.recoveryPhrase.isNotBlank(),
            isSubmitting = state.access == WalletAccessState.Connecting,
            onSubmit = { dispatch(WalletAction.Import) },
            onBack = onBack,
            onShowInstructions = onShowInstructions?.let { show ->
                {
                    clipboard.discard()
                    dispatch(WalletAction.RecoveryPhraseChanged(""))
                    show()
                }
            },
            privacyPolicyUrl = privacyPolicyUrl,
            termsUrl = termsUrl,
            modifier = modifier
        ) {
            CredentialInput(
                value = state.recoveryPhrase,
                onValueChange = {
                    clipboard.retainFor(it)
                    dispatch(WalletAction.RecoveryPhraseChanged(it))
                },
                label = stringResource(R.string.spark_wallet_phrase_label),
                placeholder = stringResource(R.string.spark_wallet_phrase_hint),
                onPaste = {
                    clipboard.read()?.trim()?.takeIf(String::isNotEmpty)?.let {
                        dispatch(WalletAction.RecoveryPhraseChanged(it))
                    }
                },
                onSubmit = {
                    if (state.access == WalletAccessState.NoWallet &&
                        state.recoveryPhrase.isNotBlank()
                    ) {
                        dispatch(WalletAction.Import)
                    }
                },
                enabled = state.access == WalletAccessState.NoWallet,
                multiline = true
            )
            PassphraseInput(state = state, dispatch = dispatch, onPaste = clipboard::read)
            Text(
                text = stringResource(R.string.spark_wallet_storage_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.message?.let { message ->
                Text(
                    text = stringResource(message.resource()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        return
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.spark_wallet_import_title)) },
                navigationIcon = {
                    onBack?.let { BackIconButton(onClick = it) }
                }
            )
        }
    ) { padding ->
        WalletConnectionContent(
            state = state,
            dispatch = dispatch,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp)
        )
    }
}

/** The optional BIP-39 passphrase, behind a link since most wallets have none. */
@Composable
private fun PassphraseInput(
    state: WalletUiState,
    dispatch: (WalletAction) -> Unit,
    onPaste: () -> String?
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val enabled = state.access == WalletAccessState.NoWallet
    if (!expanded && state.passphrase.isEmpty()) {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text(stringResource(R.string.spark_wallet_passphrase_toggle))
        }
        return
    }
    CredentialInput(
        value = state.passphrase,
        onValueChange = { dispatch(WalletAction.PassphraseChanged(it)) },
        label = stringResource(R.string.spark_wallet_passphrase_label),
        placeholder = stringResource(R.string.spark_wallet_passphrase_hint),
        // Kept exactly as copied: spaces are part of a passphrase.
        onPaste = {
            onPaste()?.takeIf(String::isNotEmpty)?.let {
                dispatch(WalletAction.PassphraseChanged(it))
            }
        },
        onSubmit = {
            if (enabled && state.recoveryPhrase.isNotBlank()) dispatch(WalletAction.Import)
        },
        enabled = enabled
    )
    Text(
        text = stringResource(R.string.spark_wallet_passphrase_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun WalletConnectionContent(
    state: WalletUiState,
    dispatch: (WalletAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (val access = state.access) {
            WalletAccessState.Loading,
            WalletAccessState.Connecting,
            WalletAccessState.Removing -> ProgressContent(access)

            WalletAccessState.NoWallet -> Unit

            WalletAccessState.ReconnectRequired -> RecoveryContent(
                title = R.string.spark_wallet_reconnect_title,
                body = R.string.spark_wallet_reconnect_body,
                dispatch = dispatch
            )

            WalletAccessState.ResetRequired -> RecoveryContent(
                title = R.string.spark_wallet_reset_title,
                body = R.string.spark_wallet_reset_body,
                dispatch = dispatch
            )

            is WalletAccessState.CredentialProblem -> CredentialProblemContent(
                kind = access.kind,
                dispatch = dispatch
            )

            WalletAccessState.Connected -> Unit
        }
    }
    if (state.confirmRemoval) {
        WalletRemovalConfirmation(dispatch)
    }
}

@Composable
private fun ProgressContent(state: WalletAccessState) {
    val resource =
        when (state) {
            WalletAccessState.Loading -> R.string.spark_wallet_progress_loading
            WalletAccessState.Connecting -> R.string.spark_wallet_progress_connecting
            WalletAccessState.Removing -> R.string.spark_wallet_progress_removing
            else -> R.string.spark_wallet_progress_working
        }
    CircularProgressIndicator()
    Text(
        text = stringResource(resource),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 20.dp)
    )
}

@Composable
private fun CredentialProblemContent(
    kind: CredentialProblemKind,
    dispatch: (WalletAction) -> Unit
) {
    val body =
        when (kind) {
            CredentialProblemKind.UNAVAILABLE -> R.string.spark_wallet_credential_unavailable
            CredentialProblemKind.INVALIDATED -> R.string.spark_wallet_credential_invalidated
            CredentialProblemKind.CORRUPT -> R.string.spark_wallet_credential_corrupt
        }
    RecoveryContent(R.string.spark_wallet_credential_title, body, dispatch)
}

@Composable
private fun RecoveryContent(
    @StringRes
    title: Int,
    @StringRes
    body: Int,
    dispatch: (WalletAction) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(
                body,
                xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Button(
            onClick = { dispatch(WalletAction.Retry) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.spark_wallet_retry))
        }
        OutlinedButton(
            onClick = { dispatch(WalletAction.RequestRemoval) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(
                    R.string.spark_wallet_reset_action,
                    xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current.uppercase()
                )
            )
        }
    }
}

@Composable
fun WalletRemovalConfirmation(dispatch: (WalletAction) -> Unit, canRemove: Boolean = true) {
    AlertDialog(
        onDismissRequest = { dispatch(WalletAction.CancelRemoval) },
        title = {
            Text(
                stringResource(
                    R.string.spark_wallet_remove_title,
                    xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
                )
            )
        },
        text = { Text(stringResource(R.string.spark_wallet_remove_body)) },
        confirmButton = {
            TextButton(
                enabled = canRemove,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                onClick = { dispatch(WalletAction.ConfirmRemoval) }
            ) {
                Text(stringResource(R.string.spark_wallet_remove_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { dispatch(WalletAction.CancelRemoval) }) {
                Text(stringResource(R.string.spark_wallet_cancel))
            }
        }
    )
}

@StringRes
private fun WalletMessage.resource(): Int = when (this) {
    WalletMessage.ALREADY_CONFIGURED -> R.string.spark_wallet_error_already_configured
    WalletMessage.INVALID_MNEMONIC -> R.string.spark_wallet_error_invalid_mnemonic
    WalletMessage.CONNECTION_FAILED -> R.string.spark_wallet_error_connection
    WalletMessage.CREDENTIAL_STORE_FAILED -> R.string.spark_wallet_error_storage
    WalletMessage.RESET_REQUIRED -> R.string.spark_wallet_error_reset
}
