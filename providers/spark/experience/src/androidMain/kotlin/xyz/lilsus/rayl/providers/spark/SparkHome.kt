package xyz.lilsus.rayl.providers.spark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletAccessState
import xyz.lilsus.rayl.providers.spark.feature.payment.sparkPaymentStringResource
import xyz.lilsus.rayl.providers.spark.feature.walletconnection.WalletAction
import xyz.lilsus.rayl.providers.spark.feature.walletconnection.WalletViewModel
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.feature.appshell.AppTab
import xyz.lilsus.raylsuite.feature.appshell.AppTabScaffold
import xyz.lilsus.raylsuite.feature.paymentflow.rememberPaymentFlowState
import xyz.lilsus.raylsuite.feature.paymentflow.rememberPaymentMessages
import xyz.lilsus.raylsuite.feature.paymenthub.PaymentHubTab
import xyz.lilsus.raylsuite.feature.paymentui.PaymentScanFlow
import xyz.lilsus.raylsuite.feature.settings.SettingsFlow
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletCardChange
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletCardsHost
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletCardsSettings

internal fun NavGraphBuilder.sparkHome(
    runtime: SparkRuntime,
    walletViewModel: WalletViewModel,
    cards: WalletCardsHost,
    onLeave: (WalletCardChange) -> Unit,
    onWalletRemoved: () -> Unit
) {
    composable<SparkDestination.Home> {
        SparkTabs(
            runtime = runtime,
            walletViewModel = walletViewModel,
            cards = cards,
            onLeave = onLeave,
            onWalletRemoved = onWalletRemoved
        )
    }
}

/** The Android tab shell. iOS renders [SparkTabContent] inside a native `TabView` instead. */
@Composable
private fun SparkTabs(
    runtime: SparkRuntime,
    walletViewModel: WalletViewModel,
    cards: WalletCardsHost,
    onLeave: (WalletCardChange) -> Unit,
    onWalletRemoved: () -> Unit
) {
    val tabState = runtime.tabState
    // Back from adding a card reopens Settings, where it was started. Decided once per wallet,
    // while composing, so the first frame already shows Settings.
    remember(runtime) {
        cards.reopensAddCard().also { if (it) tabState.select(AppTab.Settings) }
    }
    val selectedTab by tabState.selectedTab.collectAsStateWithLifecycle()

    AppTabScaffold(
        selectedTab = selectedTab,
        onTabSelected = { tab ->
            if (!runtime.paymentCoordinator.isSubmitting.value) tabState.select(tab)
        }
    ) { tab ->
        SparkTabContent(
            runtime = runtime,
            walletViewModel = walletViewModel,
            tab = tab,
            cards = cards,
            onLeave = onLeave,
            onWalletRemoved = onWalletRemoved
        )
    }
}

/** One tab's content, with no tab bar of its own. */
@Composable
internal fun SparkTabContent(
    runtime: SparkRuntime,
    walletViewModel: WalletViewModel,
    tab: AppTab,
    cards: WalletCardsHost,
    onLeave: (WalletCardChange) -> Unit,
    onWalletRemoved: () -> Unit
) {
    val coordinator = runtime.paymentCoordinator
    val isSubmitting by coordinator.isSubmitting.collectAsStateWithLifecycle()
    val tabState = runtime.tabState
    val selectedTransactionId by tabState.selectedTransactionId.collectAsStateWithLifecycle()
    val flowState = rememberPaymentFlowState(coordinator, ::sparkPaymentStringResource)
    val messages = rememberPaymentMessages(coordinator, ::sparkPaymentStringResource)
    val hubState by runtime.paymentHub.state.collectAsStateWithLifecycle()
    val currencyCode by runtime.currencyPreferences.code.collectAsStateWithLifecycle(
        CurrencyCatalog.DEFAULT_CODE
    )

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
                onIntent = coordinator::dispatch,
                onHubIntent = runtime.paymentHub::dispatch
            )

        AppTab.Hub ->
            PaymentHubTab(
                repository = runtime.paymentHubRepository,
                controller = runtime.paymentHub,
                preferredCurrencyCode = { currencyCode }
            )

        AppTab.Settings ->
            SparkSettingsTab(
                runtime = runtime,
                walletViewModel = walletViewModel,
                cards = cards,
                onLeave = onLeave,
                onWalletRemoved = onWalletRemoved
            )
    }
}

@Composable
private fun SparkSettingsTab(
    runtime: SparkRuntime,
    walletViewModel: WalletViewModel,
    cards: WalletCardsHost,
    onLeave: (WalletCardChange) -> Unit,
    onWalletRemoved: () -> Unit
) {
    val walletState by walletViewModel.state.collectAsStateWithLifecycle()
    val isSubmitting by runtime.paymentCoordinator.isSubmitting.collectAsStateWithLifecycle()

    LaunchedEffect(walletState.access) {
        if (walletState.access == WalletAccessState.NoWallet) onWalletRemoved()
    }

    WalletCardsSettings(
        host = cards,
        cardBalance = runtime.balance,
        busy = isSubmitting || walletState.access != WalletAccessState.Connected,
        onChange = { if (!runtime.paymentCoordinator.isSubmitting.value) onLeave(it) },
        onRemovePaying = {
            if (!runtime.paymentCoordinator.isSubmitting.value) {
                walletViewModel.dispatch(WalletAction.ConfirmRemoval)
            }
        }
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
