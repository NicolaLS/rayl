package xyz.lilsus.rayl.providers.nwc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
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
import xyz.lilsus.raylsuite.feature.walletmanagement.NativeWalletCardsController
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletCardsHost

/**
 * The Kotlin side of Nwc's native iOS shell. Swift owns the `TabView`; this owns the app scope
 * so every tab's view controller shares one runtime.
 */
class NwcIosExperience(
    private val configuration: NwcExperienceConfiguration,
    private val startMode: WalletStartMode,
    private val cards: WalletCardsHost
) {
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val nativeCardsControllerDelegate = lazy {
        NativeWalletCardsController(
            host = cards,
            busy = runtime.paymentCoordinator.isSubmitting,
            languageChanges = runtime.languageRepository.preference,
            onChange = cards::apply,
            onRemovePaying = { nativeWalletSettingsController.removeWallet() }
        )
    }

    private val runtimeDelegate = lazy {
        NwcRuntime(
            appSettings = createAppSettings(),
            secureSettings = createSecureSettings(configuration.credentialsName),
            walletSettings = createConnectionSettings(configuration.walletPreferencesName),
            configuration = configuration,
            startMode = startMode,
            haptics = createHapticFeedbackManager()
        )
    }
    private val runtime: NwcRuntime
        get() = runtimeDelegate.value

    init {
        // Back from adding a card reopens Settings, where it was started.
        if (cards.reopensAddCard()) runtime.tabState.select(AppTab.Settings)
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
    private val nativeSettingsController: NativeSettingsController
        get() = nativeSettingsControllerDelegate.value

    private val nativeScanControllerDelegate = lazy {
        createNativeScanController()
    }
    private val nativeScanController: NativePaymentScanController
        get() = nativeScanControllerDelegate.value

    private val nativeRecentControllerDelegate = lazy {
        createNativeRecentController()
    }
    private val nativeRecentController: NativePaymentRecentController
        get() = nativeRecentControllerDelegate.value

    private val nativeHubControllerDelegate = lazy {
        NativePaymentHubController(
            repository = runtime.paymentHubRepository,
            host = runtime.paymentHub,
            languageChanges = runtime.languageRepository.preference,
            currencyCodes = runtime.currencyPreferences.code
        )
    }
    private val nativeHubController: NativePaymentHubController
        get() = nativeHubControllerDelegate.value

    private val nativeOnboardingControllerDelegate = lazy {
        NwcNativeOnboardingController(runtime)
    }
    private val nativeOnboardingController: NwcNativeOnboardingController
        get() = nativeOnboardingControllerDelegate.value

    private val nativeWalletSettingsControllerDelegate = lazy {
        NwcNativeWalletSettingsController(runtime, nativeOnboardingController)
    }
    private val nativeWalletSettingsController: NwcNativeWalletSettingsController
        get() = nativeWalletSettingsControllerDelegate.value

    /** Payment tabs require completed setup and a connection. */
    fun isOnboarded(): Boolean = runtime.onboardingState.completed.value && isConnected()

    fun observeOnboarded(onChange: (Boolean) -> Unit): () -> Unit {
        val job = observerScope.launch {
            runtime.removalPending.collectLatest { pending ->
                if (pending) {
                    onChange(false)
                } else {
                    combine(runtime.onboardingState.completed, runtime.nwcWallet.connection) {
                            completed,
                            connection
                        ->
                        completed && connection != null
                    }.collect(onChange)
                }
            }
        }
        return { job.cancel() }
    }

    fun isConnected(): Boolean =
        !runtime.removalPending.value && runtime.nwcWallet.connection.value != null

    fun canCancelSetup(): Boolean = runtime.canAcceptWalletInput && !isConnected()

    fun observeConnected(onChange: (Boolean) -> Unit): () -> Unit {
        val job = observerScope.launch {
            runtime.removalPending.collectLatest { pending ->
                if (pending) {
                    onChange(false)
                } else {
                    runtime.nwcWallet.connection.collect { connection ->
                        // Reported first, so a card that is already added is turned back.
                        connection?.let { cards.identified("nwc:${it.walletPublicKey}") }
                        onChange(connection != null)
                    }
                }
            }
        }
        return { job.cancel() }
    }

    fun cancelSetup() {
        if (canCancelSetup()) runtime.removeWallet()
    }

    fun observeCanCancelSetup(onChange: (Boolean) -> Unit): () -> Unit =
        observeConnected { onChange(canCancelSetup()) }

    fun observeRemoved(onChange: (Boolean) -> Unit): () -> Unit {
        val job = observerScope.launch { runtime.removed.collect(onChange) }
        return { job.cancel() }
    }

    fun observeRemoval(onChange: (NwcRemovalSnapshot) -> Unit): () -> Unit {
        val job = observerScope.launch {
            combine(
                runtime.removalPending,
                runtime.removalFailed,
                runtime.languageRepository.preference
            ) { pending, failed, _ ->
                NwcRemovalSnapshot(
                    pending = pending,
                    isWorking = !failed,
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

    fun retryRemoval() {
        runtime.removeWallet()
    }

    fun clear() {
        if (nativeSettingsControllerDelegate.isInitialized()) nativeSettingsController.clear()
        if (nativeScanControllerDelegate.isInitialized()) nativeScanController.clear()
        if (nativeRecentControllerDelegate.isInitialized()) nativeRecentController.clear()
        if (nativeHubControllerDelegate.isInitialized()) nativeHubController.clear()
        if (nativeOnboardingControllerDelegate.isInitialized()) nativeOnboardingController.clear()
        if (nativeWalletSettingsControllerDelegate.isInitialized()) {
            nativeWalletSettingsController.clear()
        }
        if (nativeCardsControllerDelegate.isInitialized()) {
            nativeCardsControllerDelegate.value.clear()
        }
        observerScope.coroutineContext[Job]?.cancel()
        if (runtimeDelegate.isInitialized()) runtime.clear()
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

    /** The shared "Pay with" screens at the top of Settings. */
    fun cardsController(): NativeWalletCardsController = nativeCardsControllerDelegate.value

    fun scanController(): NativePaymentScanController = nativeScanController

    fun recentController(): NativePaymentRecentController = nativeRecentController

    fun hubController(): NativePaymentHubController = nativeHubController

    fun onboardingController(): NwcNativeOnboardingController = nativeOnboardingController

    fun walletSettingsController(): NwcNativeWalletSettingsController =
        nativeWalletSettingsController

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
                runtime.tabState.openTransaction(id)
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
                onSelectTransaction = { id ->
                    if (!runtime.paymentCoordinator.isSubmitting.value) {
                        runtime.tabState.selectTransaction(id)
                    }
                }
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
                    receiptFeeInformation = null
                )
            }.collect {}
        }
        return controller
    }
}

data class NwcRemovalSnapshot(
    val pending: Boolean,
    val isWorking: Boolean,
    val title: String,
    val message: String,
    val retryTitle: String
)
