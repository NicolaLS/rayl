package xyz.lilsus.rayl.providers.rayl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import xyz.lilsus.rayl.providers.rayl.experience.R
import xyz.lilsus.rayl.providers.rayl.feature.payment.raylPaymentStringResource
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketStatus
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRow
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRowDefaults
import xyz.lilsus.raylsuite.core.ui.components.BackIconButton
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.feature.appshell.AppTab
import xyz.lilsus.raylsuite.feature.appshell.AppTabScaffold
import xyz.lilsus.raylsuite.feature.paymentflow.rememberPaymentFlowState
import xyz.lilsus.raylsuite.feature.paymentflow.rememberPaymentMessages
import xyz.lilsus.raylsuite.feature.paymenthub.PaymentHubTab
import xyz.lilsus.raylsuite.feature.paymentui.PaymentScanFlow
import xyz.lilsus.raylsuite.feature.settings.SettingsFlow
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletBalanceRow
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletCardsHost
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletCardsSettings

/** The Android tab shell, shared with the other providers. */
@Composable
internal fun RaylTabs(runtime: RaylRuntime, cards: WalletCardsHost) {
    // Back from adding a card reopens Settings, where it was started.
    remember(runtime) { if (cards.reopensAddCard()) runtime.tabState.select(AppTab.Settings) }
    val selectedTab by runtime.tabState.selectedTab.collectAsStateWithLifecycle()
    AppTabScaffold(
        selectedTab = selectedTab,
        onTabSelected = { tab ->
            if (!runtime.paymentCoordinator.isSubmitting.value) runtime.tabState.select(tab)
        }
    ) { tab ->
        RaylTabContent(runtime, cards, tab)
    }
}

@Composable
private fun RaylTabContent(runtime: RaylRuntime, cards: WalletCardsHost, tab: AppTab) {
    val coordinator = runtime.paymentCoordinator
    val isSubmitting by coordinator.isSubmitting.collectAsStateWithLifecycle()
    val selectedTransactionId by runtime.tabState.selectedTransactionId
        .collectAsStateWithLifecycle()
    val flowState = rememberPaymentFlowState(coordinator, ::raylPaymentStringResource)
    val messages = rememberPaymentMessages(coordinator, ::raylPaymentStringResource)
    val hubState by runtime.paymentHub.state.collectAsStateWithLifecycle()
    val currencyCode by runtime.currencyPreferences.code.collectAsStateWithLifecycle(
        CurrencyCatalog.DEFAULT_CODE
    )

    when (tab) {
        AppTab.Scan -> PaymentScanFlow(
            state = flowState,
            messageEvents = messages,
            appTitle = runtime.configuration.appName,
            savePrompt = hubState.savePrompt,
            isSubmitting = isSubmitting,
            selectedTransactionId = selectedTransactionId,
            onSelectTransaction = runtime.tabState::selectTransaction,
            onIntent = coordinator::dispatch,
            onHubIntent = runtime.paymentHub::dispatch
        )

        AppTab.Hub -> PaymentHubTab(
            repository = runtime.paymentHubRepository,
            controller = runtime.paymentHub,
            preferredCurrencyCode = { currencyCode }
        )

        AppTab.Settings -> RaylSettingsTab(runtime, cards)
    }
}

/**
 * Settings with the shared "Pay with" screens. Choosing another wallet first closes the pocket
 * session (SPEC-wallet-lifecycle); the pocket itself is never removed.
 */
@Composable
private fun RaylSettingsTab(runtime: RaylRuntime, cards: WalletCardsHost) {
    val isSubmitting by runtime.paymentCoordinator.isSubmitting.collectAsStateWithLifecycle()
    WalletCardsSettings(
        host = cards,
        busy = isSubmitting,
        onChange = { runtime.useWallet(it.encode()) },
        onRemovePaying = {},
        balance = { RaylBalanceRow(runtime = runtime) },
        payingContent = { RaylSettingsActions(runtime = runtime) }
    ) { walletBlock ->
        SettingsFlow(
            themePreferences = runtime.themePreferences,
            languageRepository = runtime.languageRepository,
            bitcoinPriceProvider = runtime.bitcoinPriceProvider,
            currencyPreferences = runtime.currencyPreferences,
            paymentPreferences = runtime.paymentPreferences,
            legalLinks = runtime.configuration.legalLinks,
            overviewTopContent = walletBlock
        )
    }
}

/** The balance with Add funds, or what keeps the pocket from connecting and the way out. */
@Composable
private fun RaylBalanceRow(runtime: RaylRuntime) {
    val balance by runtime.pocket.balanceSats.collectAsStateWithLifecycle()
    val status by runtime.pocket.status.collectAsStateWithLifecycle()
    val hasRecoveryPhrase by runtime.hasRecoveryPhrase.collectAsStateWithLifecycle()
    // Only a passkey can set an unreadable key up again; without one, reading is retried.
    val unreadable = status == PocketStatus.KeyUnreadable && !hasRecoveryPhrase
    val failed = status == PocketStatus.Failed || status == PocketStatus.KeyUnreadable
    val formatter = rememberAmountFormatter()
    RaylFundingSheet(runtime.funding)
    WalletBalanceRow(
        balance = when {
            unreadable -> stringResource(R.string.rayl_key_unreadable)

            failed -> stringResource(R.string.rayl_balance_unavailable)

            else ->
                balance?.let { formatter.format(DisplayAmount(it, DisplayCurrency.Satoshi)) }
                    ?: stringResource(R.string.rayl_balance_updating)
        },
        balanceMaxLines = if (unreadable) 2 else 1,
        action = when {
            unreadable -> stringResource(R.string.rayl_set_up_again)
            failed -> stringResource(R.string.rayl_retry)
            else -> null
        },
        onAddFunds = when {
            unreadable -> runtime::setUpAgain
            failed -> runtime::retryPocket
            else -> runtime.funding::request
        }
    )
}

/** Rayl's own settings on its page while it is active: recovery and debug actions. */
@Composable
private fun RaylSettingsActions(runtime: RaylRuntime) {
    val status by runtime.pocket.status.collectAsStateWithLifecycle()
    val hasRecoveryPhrase by runtime.hasRecoveryPhrase.collectAsStateWithLifecycle()
    val isSubmitting by runtime.paymentCoordinator.isSubmitting.collectAsStateWithLifecycle()
    var confirmsErase by rememberSaveable { mutableStateOf(false) }
    var confirmsNewPocket by rememberSaveable { mutableStateOf(false) }
    // Without a passkey, an unreadable key has no other copy: only a new pocket is a way out.
    val lost = status == PocketStatus.KeyUnreadable && hasRecoveryPhrase

    Column(verticalArrangement = Arrangement.spacedBy(AppSettingsRowDefaults.RowSpacing)) {
        if (lost) {
            AppSettingsRow(
                title = stringResource(R.string.rayl_start_new_pocket),
                subtitle = stringResource(R.string.rayl_start_new_pocket_body),
                subtitleMaxLines = 3,
                icon = Icons.Filled.RestartAlt,
                iconTint = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.error,
                testTag = "rayl_start_new_pocket",
                onClick = { if (!isSubmitting) confirmsNewPocket = true }
            )
        }
        if (runtime.canErase) {
            AppSettingsRow(
                title = stringResource(R.string.rayl_erase_pocket),
                subtitle = stringResource(R.string.rayl_erase_pocket_body),
                subtitleMaxLines = 2,
                icon = Icons.Filled.DeleteForever,
                iconTint = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.error,
                onClick = { if (!isSubmitting) confirmsErase = true }
            )
        }
    }

    if (confirmsNewPocket) {
        AlertDialog(
            onDismissRequest = { confirmsNewPocket = false },
            title = { Text(stringResource(R.string.rayl_start_new_pocket_confirm)) },
            text = { Text(stringResource(R.string.rayl_start_new_pocket_confirm_body)) },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    onClick = {
                        confirmsNewPocket = false
                        runtime.startNewPocket()
                    }
                ) { Text(stringResource(R.string.rayl_start)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmsNewPocket = false }) {
                    Text(stringResource(R.string.rayl_cancel))
                }
            }
        )
    }

    if (confirmsErase) {
        AlertDialog(
            onDismissRequest = { confirmsErase = false },
            title = { Text(stringResource(R.string.rayl_erase_pocket)) },
            text = { Text(stringResource(R.string.rayl_erase_pocket_body)) },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    onClick = {
                        confirmsErase = false
                        runtime.erase()
                    }
                ) { Text(stringResource(R.string.rayl_erase)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmsErase = false }) {
                    Text(stringResource(R.string.rayl_cancel))
                }
            }
        )
    }
}
