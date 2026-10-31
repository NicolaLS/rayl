package xyz.lilsus.raylsuite.feature.walletmanagement

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRow

/**
 * The top-level settings action that disconnects an external wallet and returns to Rayl's own
 * wallet. Providers supply only their removal operation and warnings.
 */
@Composable
fun WalletRemovalAction(
    onRemoveWallet: () -> Unit,
    canRemove: Boolean = true,
    removalMessage: String? = null,
    testTag: String = "settings_remove_wallet"
) {
    var confirmsRemoval by rememberSaveable { mutableStateOf(false) }
    AppSettingsRow(
        title = stringResource(R.string.settings_manage_wallet_remove),
        onClick = { confirmsRemoval = true },
        icon = Icons.AutoMirrored.Filled.Undo,
        testTag = testTag
    )
    if (confirmsRemoval) {
        AlertDialog(
            onDismissRequest = { confirmsRemoval = false },
            title = {
                Text(stringResource(R.string.settings_manage_wallet_remove_confirmation_title))
            },
            text = {
                Text(
                    removalMessage ?: stringResource(
                        if (canRemove) {
                            R.string.wallet_removal_payment_warning
                        } else {
                            R.string.wallet_removal_payment_active
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    enabled = canRemove,
                    onClick = {
                        confirmsRemoval = false
                        onRemoveWallet()
                    }
                ) { Text(stringResource(R.string.settings_manage_wallet_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmsRemoval = false }) {
                    Text(stringResource(R.string.settings_manage_wallet_cancel))
                }
            }
        )
    }
}
