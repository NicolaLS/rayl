package xyz.lilsus.raylsuite.feature.walletmanagement

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRow

/**
 * The top-level settings action that disconnects an external wallet and returns to Rayl's own
 * wallet. It needs no confirmation: only the connection is removed, the wallet itself is untouched
 * and can be connected again. While a payment is submitted it is unavailable.
 */
@Composable
fun WalletRemovalAction(
    onRemoveWallet: () -> Unit,
    canRemove: Boolean = true,
    testTag: String = "settings_remove_wallet"
) {
    AppSettingsRow(
        title = stringResource(R.string.settings_manage_wallet_remove),
        subtitle = if (canRemove) null else stringResource(R.string.wallet_removal_payment_active),
        subtitleMaxLines = 2,
        onClick = { if (canRemove) onRemoveWallet() },
        icon = Icons.AutoMirrored.Filled.Undo,
        testTag = testTag
    )
}
