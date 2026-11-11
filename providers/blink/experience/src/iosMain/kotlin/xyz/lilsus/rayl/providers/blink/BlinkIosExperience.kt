package xyz.lilsus.rayl.providers.blink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.core.model.WalletStartMode
import xyz.lilsus.raylsuite.core.settings.createAppSettings
import xyz.lilsus.raylsuite.core.settings.createConnectionSettings
import xyz.lilsus.raylsuite.core.settings.createSecureSettings
import xyz.lilsus.raylsuite.core.ui.platform.createHapticFeedbackManager
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString
import xyz.lilsus.raylsuite.feature.appshell.AppTab
import xyz.lilsus.raylsuite.feature.appshell.appTabTitles
import xyz.lilsus.raylsuite.feature.appshell.nativeColorSchemeValue
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentEvent
import xyz.lilsus.raylsuite.feature.paymentflow.paymentErrorMessage
import xyz.lilsus.raylsuite.feature.paymentflow.toNativePaymentScreenState
import xyz.lilsus.raylsuite.feature.paymentflow.toNativeRecentItem
import xyz.lilsus.raylsuite.feature.paymenthub.NativePaymentHubController
import xyz.lilsus.raylsuite.feature.paymentui.NativePaymentRecentController
import xyz.lilsus.raylsuite.feature.paymentui.NativePaymentScanController
import xyz.lilsus.raylsuite.feature.paymentui.PaymentIntent
import xyz.lilsus.raylsuite.feature.paymentui.localizedMessage
import xyz.lilsus.raylsuite.feature.settings.NativeSettingsController
import xyz.lilsus.raylsuite.feature.settings.nativeSettingsAppVersionName

/**
 * The Kotlin side of Blink's native iOS shell. Swift owns the `TabView`; this owns the app scope
 * so every tab's view controller shares one runtime.
 */
class BlinkIosExperience(
    private val configuration: BlinkExperienceConfiguration,
    private val startMode: WalletStartMode
) {
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val runtime: BlinkRuntime by lazy {
        BlinkRuntime(
            appSettings = createAppSettings(),
            secureSettings = createSecureSettings(configuration.credentialsName),
            walletSettings = createConnectionSettings(configuration.walletPreferencesName),
            configuration = configuration,
            startMode = startMode,
            haptics = createHapticFeedbackManager()
        )
    }

    private val nativeSettingsControllerDelegate = lazy {
        NativeSettingsController(
            themePreferences = runtime.themePreferences,
            languageRepository = runtime.languageRepository,
            currencyPreferences = runtime.currencyPreferences,
            paymentPreferences = runtime.paymentPreferences,
            bitcoinPriceProvider = runtime.bitcoinPriceProvider,
            legalLinks = configuration.legalLinks,
            appVersionName = nativeSettingsAppVersionName()
        )
    }
    private val nativeSettingsController by nativeSettingsControllerDelegate

    private val blinkNativeSettingsControllerDelegate = lazy {
        BlinkNativeSettingsController(
            blinkWallet = runtime.blinkWallet,
            languageRepository = runtime.languageRepository,
            isSubmitting = runtime.paymentCoordinator.isSubmitting,
            appName = configuration.appName,
            onRemoveWallet = ::removeWallet
        )
    }
    private val blinkNativeSettingsController by blinkNativeSettingsControllerDelegate

    private val nativeScanControllerDelegate = lazy {
        createNativeScanController()
    }
    private val nativeScanController by nativeScanControllerDelegate

    private val nativeRecentControllerDelegate = lazy {
        createNativeRecentController()
    }
    private val nativeRecentController by nativeRecentControllerDelegate

    private val nativeHubControllerDelegate = lazy {
        NativePaymentHubController(
            repository = runtime.paymentHubRepository,
            host = runtime.paymentHub,
            languageChanges = runtime.languageRepository.preference,
            currencyCodes = runtime.currencyPreferences.code
        )
    }
    private val nativeHubController by nativeHubControllerDelegate

    private val nativeOnboardingControllerDelegate = lazy {
        BlinkNativeOnboardingController(
            onboarding = runtime.onboardingViewModel,
            blinkWallet = runtime.blinkWallet,
            languageChanges = runtime.languageRepository.preference,
            appName = configuration.appName,
            legalLinks = configuration.legalLinks,
            progress = runtime.onboardingState,
            canConnectWallet = { runtime.canConnect },
            onCancelSetup = ::cancelSetup
        )
    }
    private val nativeOnboardingController by nativeOnboardingControllerDelegate

    /** Connection and app education have independent lifetimes. */
    fun isOnboarded(): Boolean =
        !runtime.removalPending.value && runtime.onboardingState.canEnterApp(isConnected())

    fun observeOnboarded(onChange: (Boolean) -> Unit): () -> Unit {
        val job =
            observerScope.launch {
                combine(
                    runtime.connected,
                    runtime.onboardingState.step
                ) { connected, _ -> runtime.onboardingState.canEnterApp(connected) }
                    .collect(onChange)
            }
        return { job.cancel() }
    }

    fun isConnected(): Boolean = runtime.hasStarted && runtime.blinkWallet.connection.value != null

    fun canCancelSetup(): Boolean = runtime.canConnect && !isConnected()

    fun cancelSetup() {
        if (canCancelSetup()) runtime.removeWallet()
    }

    fun observeCanCancelSetup(onChange: (Boolean) -> Unit): () -> Unit {
        val job = observerScope.launch {
            combine(runtime.connected, runtime.removalPending, runtime.removed) {
                    connected,
                    pending,
                    removed
                ->
                !connected && !pending && !removed
            }.collect(onChange)
        }
        return { job.cancel() }
    }

    fun observeConnected(onChange: (Boolean) -> Unit): () -> Unit {
        val job = observerScope.launch {
            runtime.connected.collect(onChange)
        }
        return { job.cancel() }
    }

    fun observeRemoved(onChange: (Boolean) -> Unit): () -> Unit {
        val job = observerScope.launch { runtime.removed.collect(onChange) }
        return { job.cancel() }
    }

    fun clear() {
        if (nativeOnboardingControllerDelegate.isInitialized()) nativeOnboardingController.clear()
        if (nativeScanControllerDelegate.isInitialized()) nativeScanController.clear()
        if (nativeRecentControllerDelegate.isInitialized()) nativeRecentController.clear()
        if (nativeHubControllerDelegate.isInitialized()) nativeHubController.clear()
        if (nativeSettingsControllerDelegate.isInitialized()) nativeSettingsController.clear()
        if (blinkNativeSettingsControllerDelegate.isInitialized()) {
            blinkNativeSettingsController.clear()
        }
        observerScope.coroutineContext[Job]?.cancel()
        runtime.clear()
    }

    fun selectedTab(): String = runtime.tabState.selectedTab.value.storedValue

    fun observeSelectedTab(onChange: (String) -> Unit): () -> Unit {
        val job =
            observerScope.launch {
                runtime.tabState.selectedTab.collect { onChange(it.storedValue) }
            }
        return { job.cancel() }
    }

    fun observeTheme(onChange: (String) -> Unit): () -> Unit {
        val job =
            observerScope.launch {
                runtime.themePreferences.preference.collect {
                    onChange(it.nativeColorSchemeValue())
                }
            }
        return { job.cancel() }
    }

    /** Localized tab titles keyed by tab ID, so the native tab bar uses one set of strings. */
    fun observeTabTitles(onChange: (Map<String, String>) -> Unit): () -> Unit {
        val job =
            observerScope.launch {
                runtime.languageRepository.preference.collect { onChange(appTabTitles()) }
            }
        return { job.cancel() }
    }

    fun selectTab(tab: String) {
        if (!runtime.paymentCoordinator.isSubmitting.value) {
            runtime.tabState.select(AppTab.fromStoredValue(tab))
        }
    }

    fun settingsController(): NativeSettingsController = nativeSettingsController

    fun blinkSettingsController(): BlinkNativeSettingsController = blinkNativeSettingsController

    fun scanController(): NativePaymentScanController = nativeScanController

    fun recentController(): NativePaymentRecentController = nativeRecentController

    fun hubController(): NativePaymentHubController = nativeHubController

    fun onboardingController(): BlinkNativeOnboardingController = nativeOnboardingController

    private fun removeWallet() {
        runtime.removeWallet()
    }

    fun retryRemoval() = runtime.removeWallet()

    fun observeRemovalRecovery(onChange: (BlinkRemovalRecoverySnapshot) -> Unit): () -> Unit {
        val job = observerScope.launch {
            combine(
                runtime.removalPending,
                runtime.isRemoving,
                runtime.removed,
                runtime.languageRepository.preference
            ) { pending, removing, removed, _ ->
                BlinkRemovalRecoverySnapshot(
                    required = pending || removed,
                    isWorking = removing || removed,
                    title = nativeString(
                        NativeStringResource("WalletManagement", "settings_manage_wallet_remove")
                    ),
                    message = nativeString(
                        NativeStringResource("WalletManagement", "wallet_removal_failed")
                    ),
                    retryTitle = nativeString(
                        NativeStringResource("WalletManagement", "wallet_removal_retry")
                    )
                )
            }.collect(onChange)
        }
        return { job.cancel() }
    }

    private fun createNativeScanController(): NativePaymentScanController {
        val controller =
            NativePaymentScanController(
                onPaymentIntent = runtime.paymentCoordinator::dispatch,
                onHubIntent = runtime.paymentHub::dispatch
            )
        observerScope.launch {
            combine(
                runtime.paymentCoordinator.uiState,
                runtime.paymentCoordinator.sessionTransactions,
                runtime.paymentCoordinator.newSessionTransactionCount,
                runtime.paymentHub.state,
                runtime.languageRepository.preference
            ) { payment, transactions, newTransactionCount, hubState, _ ->
                controller.update(
                    payment = payment.toNativePaymentScreenState(),
                    appTitle = configuration.appName,
                    savePrompt = hubState.savePrompt,
                    recentCount = transactions.size,
                    newRecentCount = newTransactionCount
                )
            }.collect {}
        }
        observerScope.launch {
            runtime.paymentCoordinator.events.collect { event ->
                val message =
                    when (event) {
                        is PaymentEvent.ShowError -> paymentErrorMessage(event.error)
                        is PaymentEvent.ShowToast -> event.message.localizedMessage()
                    }
                controller.emitMessage(message)
            }
        }
        observerScope.launch {
            runtime.paymentCoordinator.transactionDetailNavigationTarget.collect { id ->
                if (id == null) return@collect
                runtime.tabState.selectTransaction(id)
                runtime.tabState.requestScan()
                runtime.paymentCoordinator.dispatch(
                    PaymentIntent.TransactionDetailNavigationHandled(id)
                )
            }
        }
        return controller
    }

    private fun createNativeRecentController(): NativePaymentRecentController {
        val controller =
            NativePaymentRecentController(
                onIntent = runtime.paymentCoordinator::dispatch,
                onSelectTransaction = runtime.tabState::selectTransaction
            )
        observerScope.launch {
            combine(
                runtime.paymentCoordinator.sessionTransactions,
                runtime.tabState.selectedTransactionId,
                runtime.languageRepository.preference
            ) { transactions, selectedId, _ ->
                controller.update(
                    items = transactions.map { it.toNativeRecentItem() },
                    selectedTransactionId = selectedId,
                    receiptFeeInformation =
                        nativeString(
                            NativeStringResource(
                                table = "BlinkUI",
                                key = "receipt_fee_blink_hint"
                            )
                        )
                )
            }.collect {}
        }
        return controller
    }
}

data class BlinkRemovalRecoverySnapshot(
    val required: Boolean,
    val isWorking: Boolean,
    val title: String,
    val message: String,
    val retryTitle: String
)
