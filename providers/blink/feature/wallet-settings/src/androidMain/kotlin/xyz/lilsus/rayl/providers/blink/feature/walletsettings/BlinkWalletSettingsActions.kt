package xyz.lilsus.rayl.providers.blink.feature.walletsettings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.lilsus.rayl.providers.blink.feature.walletsettings.R
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkFundingWallet
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkWalletCurrency
import xyz.lilsus.rayl.providers.blink.ui.blinkErrorMessageFor
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRow
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRowDefaults

/** Android renderer for Blink's wallet settings actions. */
@Composable
fun BlinkWalletSettingsActions(
    state: BlinkWalletSettingsUiState,
    onLoadFundingWallets: () -> Unit,
    onSelectFundingWallet: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showFundingWallets by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSettingsRowDefaults.RowSpacing)
    ) {
        AppSettingsRow(
            title = stringResource(R.string.settings_blink_funding_wallet),
            onClick = {
                showFundingWallets = true
                onLoadFundingWallets()
            },
            subtitle =
                state.selectedWallet?.currency?.fundingWalletTitle()
                    ?: stringResource(R.string.settings_blink_funding_wallet_choose),
            icon = Icons.Filled.AccountBalanceWallet,
            testTag = BlinkWalletSettingsTestTags.FUNDING_WALLET_BUTTON
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(AppSettingsRowDefaults.ChevronSize)
            )
        }
    }

    if (showFundingWallets) {
        FundingWalletDialog(
            state = state,
            onSelect = { wallet ->
                onSelectFundingWallet(wallet.id)
                showFundingWallets = false
            },
            onDismiss = { showFundingWallets = false }
        )
    }
}

@Composable
private fun FundingWalletDialog(
    state: BlinkWalletSettingsUiState,
    onSelect: (BlinkFundingWallet) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_blink_funding_wallet_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.isLoading) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Text(stringResource(R.string.settings_blink_funding_wallet_loading))
                    }
                }
                if (state.selectionUnavailable) {
                    Text(
                        text =
                            stringResource(
                                R.string.settings_blink_funding_wallet_unavailable
                            ),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                state.error?.let { error ->
                    Text(
                        text = blinkErrorMessageFor(error),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (!state.isLoading) {
                    state.wallets.forEach { wallet ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(wallet) }
                                    .testTag(
                                        BlinkWalletSettingsTestTags.walletOption(wallet.currency)
                                    ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = state.selectedWallet?.id == wallet.id,
                                onClick = { onSelect(wallet) }
                            )
                            Text(wallet.currency.fundingWalletTitle())
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_blink_funding_wallet_close))
            }
        }
    )
}

@Composable
private fun BlinkWalletCurrency.fundingWalletTitle(): String = stringResource(
    when (this) {
        BlinkWalletCurrency.BTC -> R.string.settings_blink_funding_wallet_bitcoin
        BlinkWalletCurrency.USD -> R.string.settings_blink_funding_wallet_stablesats
    }
)

object BlinkWalletSettingsTestTags {
    const val FUNDING_WALLET_BUTTON = "settings_blink_funding_wallet"

    fun walletOption(currency: BlinkWalletCurrency): String =
        "settings_blink_funding_wallet_${currency.name.lowercase()}"
}
