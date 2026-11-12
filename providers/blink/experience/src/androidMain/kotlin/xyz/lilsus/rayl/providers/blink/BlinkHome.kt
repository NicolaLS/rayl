package xyz.lilsus.rayl.providers.blink

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import xyz.lilsus.rayl.providers.blink.feature.payment.blinkPaymentStringResource
import xyz.lilsus.rayl.providers.blink.feature.walletsettings.BlinkWalletSettingsActions
import xyz.lilsus.rayl.providers.blink.feature.walletsettings.BlinkWalletSettingsViewModel
import xyz.lilsus.rayl.providers.blink.ui.R as BlinkUiR
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.feature.appshell.AppTab
import xyz.lilsus.raylsuite.feature.appshell.AppTabScaffold
import xyz.lilsus.raylsuite.feature.paymentflow.rememberPaymentFlowState
import xyz.lilsus.raylsuite.feature.paymentflow.rememberPaymentMessages
import xyz.lilsus.raylsuite.feature.paymenthub.PaymentHubTab
import xyz.lilsus.raylsuite.feature.paymentui.PaymentScanFlow
import xyz.lilsus.raylsuite.feature.settings.SettingsFlow
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletCardsHost
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletCardsSettings

internal fun NavGraphBuilder.blinkHome(
    runtime: BlinkRuntime,
    cards: WalletCardsHost,
    onRemoveWallet: () -> Unit
) {
    composable<BlinkDestination.Home> {
        BlinkTabs(
            runtime = runtime,
            cards = cards,
            onRemoveWallet = onRemoveWallet
        )
    }
}

/** Blink's Android tab shell. iOS renders its tabs directly with SwiftUI. */
@Composable
private fun BlinkTabs(runtime: BlinkRuntime, cards: WalletCardsHost, onRemoveWallet: () -> Unit) {
    val tabState = runtime.tabState
    // Back from adding a card reopens Settings, where it was started.
    remember(runtime) { if (cards.reopensAddCard()) tabState.select(AppTab.Settings) }
    val selectedTab by tabState.selectedTab.collectAsStateWithLifecycle()

    AppTabScaffold(
        selectedTab = selectedTab,
        onTabSelected = { tab ->
            if (!runtime.paymentCoordinator.isSubmitting.value) tabState.select(tab)
        }
    ) { tab ->
        BlinkTabContent(
            runtime = runtime,
            tab = tab,
            cards = cards,
            onRemoveWallet = onRemoveWallet
        )
    }
}

/** One tab's content, with no tab bar of its own. */
@Composable
internal fun BlinkTabContent(
    runtime: BlinkRuntime,
    tab: AppTab,
    cards: WalletCardsHost,
    onRemoveWallet: () -> Unit
) {
    val coordinator = runtime.paymentCoordinator
    val isSubmitting by coordinator.isSubmitting.collectAsStateWithLifecycle()
    val tabState = runtime.tabState
    val selectedTransactionId by tabState.selectedTransactionId.collectAsStateWithLifecycle()
    val flowState = rememberPaymentFlowState(coordinator, ::blinkPaymentStringResource)
    val messages = rememberPaymentMessages(coordinator, ::blinkPaymentStringResource)
    val hubState by runtime.paymentHub.state.collectAsStateWithLifecycle()
    val currencyCode by runtime.currencyPreferences.code.collectAsStateWithLifecycle(
        CurrencyCatalog.DEFAULT_CODE
    )
    val receiptFeeInformation = stringResource(BlinkUiR.string.receipt_fee_blink_hint)

    when (tab) {
        AppTab.Scan ->
            PaymentScanFlow(
                state = flowState,
                messageEvents = messages,
                appTitle = runtime.configuration.appName,
                savePrompt = hubState.savePrompt,
                isSubmitting = isSubmitting,
                selectedTransactionId = selectedTransactionId,
                onSelectTransaction = tabState::selectTransaction,
                receiptFeeInformation = receiptFeeInformation,
                onIntent = coordinator::dispatch,
                onHubIntent = runtime.paymentHub::dispatch
            )

        AppTab.Hub -> BlinkHubTab(runtime = runtime, currencyCode = currencyCode)

        AppTab.Settings ->
            BlinkSettingsTab(
                runtime = runtime,
                cards = cards,
                onRemoveWallet = onRemoveWallet
            )
    }
}

@Composable
private fun BlinkHubTab(runtime: BlinkRuntime, currencyCode: String) {
    PaymentHubTab(
        repository = runtime.paymentHubRepository,
        controller = runtime.paymentHub,
        preferredCurrencyCode = { currencyCode }
    )
}

@Composable
private fun BlinkSettingsTab(
    runtime: BlinkRuntime,
    cards: WalletCardsHost,
    onRemoveWallet: () -> Unit
) {
    val isSubmitting by runtime.paymentCoordinator.isSubmitting.collectAsStateWithLifecycle()
    val walletSettingsViewModel =
        remember(runtime.blinkWallet) {
            BlinkWalletSettingsViewModel(runtime.blinkWallet)
        }
    val walletSettingsState by walletSettingsViewModel.uiState.collectAsStateWithLifecycle()
    DisposableEffect(walletSettingsViewModel) {
        onDispose(walletSettingsViewModel::clear)
    }
    val fundingWallet: @Composable () -> Unit = {
        BlinkWalletSettingsActions(
            state = walletSettingsState,
            onLoadFundingWallets = walletSettingsViewModel::loadFundingWallets,
            onSelectFundingWallet = walletSettingsViewModel::selectFundingWallet
        )
    }
    // Blink keeps no session that must close: the host unmounts it right away.
    WalletCardsSettings(
        host = cards,
        busy = isSubmitting,
        onChange = { if (!runtime.paymentCoordinator.isSubmitting.value) cards.apply(it) },
        onRemovePaying = { if (!runtime.paymentCoordinator.isSubmitting.value) onRemoveWallet() },
        payingContent = fundingWallet
    ) { walletBlock ->
        SettingsFlow(
            themePreferences = runtime.themePreferences,
            languageRepository = runtime.languageRepository,
            bitcoinPriceProvider = runtime.bitcoinPriceProvider,
            currencyPreferences = runtime.currencyPreferences,
            paymentPreferences = runtime.paymentPreferences,
            legalLinks = runtime.configuration.legalLinks,
            modifier = Modifier,
            overviewTopContent = walletBlock
        )
    }
}
