package xyz.lilsus.rayl.providers.spark

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletAccessState
import xyz.lilsus.rayl.providers.spark.feature.payment.rememberPaymentFlowState
import xyz.lilsus.rayl.providers.spark.feature.payment.rememberPaymentMessages
import xyz.lilsus.rayl.providers.spark.feature.walletconnection.WalletAction
import xyz.lilsus.rayl.providers.spark.feature.walletconnection.WalletViewModel
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.feature.appshell.AppTab
import xyz.lilsus.raylsuite.feature.appshell.AppTabScaffold
import xyz.lilsus.raylsuite.feature.paymenthub.PaymentHubTab
import xyz.lilsus.raylsuite.feature.paymentui.PaymentScanFlow
import xyz.lilsus.raylsuite.feature.settings.SettingsFlow
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletRemovalAction

internal fun NavGraphBuilder.sparkHome(
    runtime: SparkRuntime,
    walletViewModel: WalletViewModel,
    onWalletRemoved: () -> Unit
) {
    composable<SparkDestination.Home> {
        SparkTabs(
            runtime = runtime,
            walletViewModel = walletViewModel,
            onWalletRemoved = onWalletRemoved
        )
    }
}

/** The Android tab shell. iOS renders [SparkTabContent] inside a native `TabView` instead. */
@Composable
private fun SparkTabs(
    runtime: SparkRuntime,
    walletViewModel: WalletViewModel,
    onWalletRemoved: () -> Unit
) {
    val tabState = runtime.tabState
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
    onWalletRemoved: () -> Unit
) {
    val coordinator = runtime.paymentCoordinator
    val isSubmitting by coordinator.isSubmitting.collectAsStateWithLifecycle()
    val tabState = runtime.tabState
    val selectedTransactionId by tabState.selectedTransactionId.collectAsStateWithLifecycle()
    val flowState = rememberPaymentFlowState(coordinator)
    val messages = rememberPaymentMessages(coordinator)
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
                onWalletRemoved = onWalletRemoved
            )
    }
}

@Composable
private fun SparkSettingsTab(
    runtime: SparkRuntime,
    walletViewModel: WalletViewModel,
    onWalletRemoved: () -> Unit
) {
    val walletState by walletViewModel.state.collectAsStateWithLifecycle()
    val isSubmitting by runtime.paymentCoordinator.isSubmitting.collectAsStateWithLifecycle()

    LaunchedEffect(walletState.access) {
        if (walletState.access == WalletAccessState.NoWallet) onWalletRemoved()
    }

    SettingsFlow(
        themePreferences = runtime.themePreferences,
        languageRepository = runtime.languageRepository,
        bitcoinPriceProvider = runtime.bitcoinPriceProvider,
        currencyPreferences = runtime.currencyPreferences,
        paymentPreferences = runtime.paymentPreferences,
        legalLinks = runtime.configuration.legalLinks,
        modifier = Modifier,
        overviewBottomContent = {
            WalletRemovalAction(
                canRemove = !isSubmitting && walletState.access == WalletAccessState.Connected,
                onRemoveWallet = {
                    if (!runtime.paymentCoordinator.isSubmitting.value) {
                        walletViewModel.dispatch(WalletAction.ConfirmRemoval)
                    }
                }
            )
        }
    )
}
