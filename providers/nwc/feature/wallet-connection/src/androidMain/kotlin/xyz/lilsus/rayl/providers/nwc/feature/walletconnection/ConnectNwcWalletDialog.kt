package xyz.lilsus.rayl.providers.nwc.feature.walletconnection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import xyz.lilsus.rayl.providers.nwc.feature.walletconnection.R
import xyz.lilsus.rayl.providers.nwc.integration.nwc.NwcWalletDiscovery
import xyz.lilsus.raylsuite.core.ui.keyboard.doneKeyboardPlatformImeOptions

@Composable
fun ConnectNwcWalletDialog(
    state: ConnectNwcWalletUiState,
    onAliasChange: (String) -> Unit,
    onRetryDiscovery: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        modifier = Modifier.testTag(NwcWalletConnectionTestTags.CONFIRM_DIALOG),
        onDismissRequest = { if (!state.isSaving) onCancel() },
        properties = DialogProperties(
            dismissOnBackPress = !state.isSaving,
            dismissOnClickOutside = !state.isSaving
        ),
        title = { Text(stringResource(R.string.connect_wallet_title)) },
        text = {
            ConnectDialogContent(
                state = state,
                onAliasChange = onAliasChange,
                onRetryDiscovery = onRetryDiscovery
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled =
                    state.discovery?.supportsRequiredMethods == true &&
                        !state.isSaving &&
                        !state.isDiscoveryLoading,
                modifier =
                    Modifier.testTag(
                        NwcWalletConnectionTestTags.DIALOG_CONFIRM_BUTTON
                    )
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier =
                            Modifier
                                .padding(end = 8.dp)
                                .size(18.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text(stringResource(R.string.connect_wallet_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !state.isSaving,
                modifier =
                    Modifier.testTag(
                        NwcWalletConnectionTestTags.DIALOG_CANCEL_BUTTON
                    )
            ) {
                Text(stringResource(R.string.connect_wallet_cancel))
            }
        }
    )
}

@Composable
private fun ConnectDialogContent(
    state: ConnectNwcWalletUiState,
    onAliasChange: (String) -> Unit,
    onRetryDiscovery: () -> Unit
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.connect_wallet_description))
        state.discovery?.let { discovery ->
            WarningSection(discovery)
        }

        when {
            state.isDiscoveryLoading -> DiscoveryLoading()

            state.discovery != null ->
                DiscoveryDetails(
                    state = state,
                    onAliasChange = onAliasChange
                )
        }

        state.error?.let { error ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = nwcConnectionErrorMessageFor(error),
                    color = MaterialTheme.colorScheme.error
                )
                if (!state.isDiscoveryLoading && state.discovery == null) {
                    TextButton(
                        onClick = onRetryDiscovery,
                        modifier =
                            Modifier.testTag(
                                NwcWalletConnectionTestTags.DIALOG_RETRY_BUTTON
                            )
                    ) {
                        Text(stringResource(R.string.connect_wallet_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryLoading() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(NwcWalletConnectionTestTags.DIALOG_LOADING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp
        )
        Text(stringResource(R.string.connect_wallet_loading))
    }
}

@Composable
private fun DiscoveryDetails(state: ConnectNwcWalletUiState, onAliasChange: (String) -> Unit) {
    val discovery = state.discovery ?: return
    val focusManager = LocalFocusManager.current
    val finishAliasEditing = { focusManager.clearFocus(force = true) }
    val doneLabel = stringResource(R.string.keyboard_done)

    Column(
        modifier = Modifier.testTag(NwcWalletConnectionTestTags.DIALOG_DETAILS),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = state.alias,
            onValueChange = onAliasChange,
            label = { Text(stringResource(R.string.connect_wallet_alias_label)) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(NwcWalletConnectionTestTags.DIALOG_ALIAS_FIELD),
            enabled = !state.isSaving,
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    imeAction = ImeAction.Done,
                    platformImeOptions =
                        doneKeyboardPlatformImeOptions(
                            doneLabel = doneLabel,
                            onDone = finishAliasEditing
                        )
                ),
            keyboardActions =
                KeyboardActions(
                    onDone = { finishAliasEditing() }
                )
        )

        WalletSummary(discovery)
    }
}

@Composable
private fun WalletSummary(discovery: NwcWalletDiscovery) {
    if (discovery.lightningAddress == null && discovery.relayUrl == null) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        discovery.lightningAddress?.let { address ->
            Text(
                text = stringResource(R.string.connect_wallet_details_lud16),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SelectableDetailValue(address)
        }
        discovery.relayUrl?.let { relay ->
            if (discovery.lightningAddress != null) {
                Spacer(Modifier.size(4.dp))
            }
            Text(
                text = stringResource(R.string.connect_wallet_details_relay),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SelectableDetailValue(relay)
        }
    }
}

@Composable
private fun SelectableDetailValue(value: String) {
    SelectionContainer {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun WarningSection(discovery: NwcWalletDiscovery) {
    val warnings =
        buildList {
            if (!discovery.supportsRequiredMethods) {
                add(
                    stringResource(
                        R.string.connect_wallet_required_methods,
                        xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
                    )
                )
            }
            if (!discovery.supportsPayInvoice) {
                add(
                    stringResource(
                        R.string.connect_wallet_warning_missing_pay_invoice
                    )
                )
            }
            if (!discovery.supportsLookupInvoice) {
                add(
                    stringResource(
                        R.string.connect_wallet_warning_missing_lookup_invoice,
                        xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
                    )
                )
            }
            when {
                discovery.usesLegacyEncryption &&
                    discovery.metadata.encryptionDefaultedToNip04 ->
                    add(
                        stringResource(
                            R.string.connect_wallet_warning_legacy_nip04_default
                        )
                    )

                discovery.usesLegacyEncryption ->
                    add(
                        stringResource(
                            R.string.connect_wallet_warning_legacy_nip04
                        )
                    )

                !discovery.supportsNip44 ->
                    add(
                        stringResource(
                            R.string.connect_wallet_warning_missing_nip44
                        )
                    )
            }
        }
    if (warnings.isEmpty()) return

    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(NwcWalletConnectionTestTags.DIALOG_WARNING)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.connect_wallet_warning_heading),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.labelMedium
            )
            warnings.forEach { warning ->
                Text(
                    text = warning,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
