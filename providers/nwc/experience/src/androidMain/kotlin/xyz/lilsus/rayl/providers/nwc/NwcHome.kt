package xyz.lilsus.rayl.providers.nwc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import xyz.lilsus.rayl.providers.nwc.feature.onboarding.NwcOnboardingDestination
import xyz.lilsus.rayl.providers.nwc.feature.onboarding.nwcOnboarding
import xyz.lilsus.rayl.providers.nwc.feature.payment.nwcPaymentStringResource
import xyz.lilsus.rayl.providers.nwc.feature.walletdetails.NwcWalletDetailsScreen
import xyz.lilsus.rayl.providers.nwc.feature.walletdetails.R as NwcDetailsR
import xyz.lilsus.rayl.providers.nwc.integration.nwc.NwcWalletConnection
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRow
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRowDefaults
import xyz.lilsus.raylsuite.core.ui.platform.rememberCredentialClipboard
import xyz.lilsus.raylsuite.feature.appshell.AppTab
import xyz.lilsus.raylsuite.feature.appshell.AppTabScaffold
import xyz.lilsus.raylsuite.feature.paymentflow.rememberPaymentFlowState
import xyz.lilsus.raylsuite.feature.paymentflow.rememberPaymentMessages
import xyz.lilsus.raylsuite.feature.paymenthub.PaymentHubTab
import xyz.lilsus.raylsuite.feature.paymentui.PaymentScanFlow
import xyz.lilsus.raylsuite.feature.settings.SettingsFlow
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletRemovalAction

internal fun NavGraphBuilder.nwcHome(runtime: NwcRuntime) {
    composable<NwcDestination.Home> {
        NwcTabs(
            runtime = runtime
        )
    }
}

/** The Android tab shell. iOS renders [NwcTabContent] inside a native `TabView` instead. */
@Composable
private fun NwcTabs(runtime: NwcRuntime) {
    val tabState = runtime.tabState
    val selectedTab by tabState.selectedTab.collectAsStateWithLifecycle()

    AppTabScaffold(
        selectedTab = selectedTab,
        onTabSelected = { tab ->
            if (!runtime.paymentCoordinator.isSubmitting.value) tabState.select(tab)
        }
    ) { tab ->
        NwcTabContent(
            runtime = runtime,
            tab = tab
        )
    }
}

/** One tab's content, with no tab bar of its own. */
@Composable
internal fun NwcTabContent(runtime: NwcRuntime, tab: AppTab) {
    val coordinator = runtime.paymentCoordinator
    val isSubmitting by coordinator.isSubmitting.collectAsStateWithLifecycle()
    val tabState = runtime.tabState
    val selectedTransactionId by tabState.selectedTransactionId.collectAsStateWithLifecycle()
    val flowState = rememberPaymentFlowState(coordinator, ::nwcPaymentStringResource)
    val messages = rememberPaymentMessages(coordinator, ::nwcPaymentStringResource)
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
            NwcSettingsTab(
                runtime = runtime
            )
    }
}

@Composable
private fun NwcSettingsTab(runtime: NwcRuntime) {
    var destination by rememberSaveable { mutableStateOf(NwcSettingsDestination.Root) }
    val connection by runtime.nwcWallet.connection.collectAsStateWithLifecycle()
    val isSubmitting by runtime.paymentCoordinator.isSubmitting.collectAsStateWithLifecycle()
    val walletFlowRequested by runtime.settingsWalletFlow.collectAsStateWithLifecycle()

    NavigationEventHandler(
        state = rememberNavigationEventState(currentInfo = NwcSettingsNavigationInfo(destination)),
        isForwardEnabled = false,
        isBackEnabled = destination == NwcSettingsDestination.WalletDetails,
        onBackCompleted = {
            destination = NwcSettingsDestination.Root
        }
    )

    LaunchedEffect(walletFlowRequested) {
        if (walletFlowRequested) destination = NwcSettingsDestination.WalletFlow
    }

    when (destination) {
        NwcSettingsDestination.Root ->
            SettingsFlow(
                themePreferences = runtime.themePreferences,
                languageRepository = runtime.languageRepository,
                bitcoinPriceProvider = runtime.bitcoinPriceProvider,
                currencyPreferences = runtime.currencyPreferences,
                paymentPreferences = runtime.paymentPreferences,
                legalLinks = runtime.configuration.legalLinks,
                modifier = Modifier,
                overviewBottomContent = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(
                            AppSettingsRowDefaults.RowSpacing
                        )
                    ) {
                        connection?.let { current ->
                            AppSettingsRow(
                                title = stringResource(
                                    NwcDetailsR.string.settings_wallet_details_title
                                ),
                                subtitle = formatWalletSubtitle(current),
                                icon = Icons.Filled.AccountBalanceWallet,
                                onClick = { destination = NwcSettingsDestination.WalletDetails }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(AppSettingsRowDefaults.ChevronSize)
                                )
                            }
                        }
                        WalletRemovalAction(
                            onRemoveWallet = runtime::removeWallet,
                            canRemove = !isSubmitting
                        )
                    }
                }
            )

        NwcSettingsDestination.WalletDetails -> {
            val current = connection
            if (current == null) {
                LaunchedEffect(Unit) { destination = NwcSettingsDestination.Root }
            } else {
                NwcWalletDetailsScreen(
                    connection = current,
                    onBack = { destination = NwcSettingsDestination.Root }
                )
            }
        }

        NwcSettingsDestination.WalletFlow ->
            NwcWalletFlow(
                runtime = runtime,
                onFinished = {
                    runtime.walletFlowHandled()
                    destination = NwcSettingsDestination.Root
                }
            )
    }
}

/** Connecting a wallet from settings. It runs inside the Settings tab so the tab bar stays put. */
@Composable
private fun NwcWalletFlow(runtime: NwcRuntime, onFinished: () -> Unit) {
    val clipboard = rememberCredentialClipboard()
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = NwcOnboardingDestination.AddWalletFromSettings
    ) {
        nwcOnboarding(
            navController = navController,
            nwcWallet = runtime.nwcWallet,
            onboardingViewModel = runtime.onboardingViewModel,
            connectionDraft = runtime.connectionDraft,
            clipboard = clipboard,
            privacyPolicyUrl = runtime.configuration.legalLinks.privacyPolicyUrl,
            termsUrl = runtime.configuration.legalLinks.termsUrl,
            onWalletConnected = onFinished
        )
    }
}

private enum class NwcSettingsDestination {
    Root,
    WalletDetails,
    WalletFlow
}

private data class NwcSettingsNavigationInfo(val destination: NwcSettingsDestination) :
    NavigationEventInfo()

private fun formatWalletSubtitle(connection: NwcWalletConnection): String {
    connection.alias?.takeIf(String::isNotBlank)?.let { return it }
    val publicKey = connection.walletPublicKey
    return if (publicKey.length <= 12) {
        publicKey
    } else {
        "${publicKey.take(6)}…${publicKey.takeLast(4)}"
    }
}
