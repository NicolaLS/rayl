package xyz.lilsus.rayl.providers.spark

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletAccessState
import xyz.lilsus.raylsuite.core.settings.createAppSettings
import xyz.lilsus.raylsuite.core.ui.platform.createHapticFeedbackManager
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
 * The Kotlin side of Spark's native iOS shell. Swift owns the `TabView`; this owns the app scope
 * so every tab's view controller shares one runtime.
 */
class SparkIosExperience(
    host: SparkAppHost,
    private val configuration: SparkExperienceConfiguration,
    private val onCancelSetup: () -> Unit = {}
) {
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var closed = false

    private val runtime =
        SparkRuntime(
            host = host,
            configuration = configuration,
            appSettings = createAppSettings(),
            haptics = createHapticFeedbackManager()
        )

    private val nativeSettingsControllerDelegate = lazy {
        NativeSettingsController(
            themePreferences = runtime.themePreferences,
            languageRepository = runtime.languageRepository,
            currencyPreferences = runtime.currencyPreferences,
            paymentPreferences = runtime.paymentPreferences,
            bitcoinPriceProvider = runtime.bitcoinPriceProvider,
            legalLinks = configuration.legalLinks,
            appVersionName = nativeSettingsAppVersionName()
        ).also { if (closed) it.clear() }
    }
    private val nativeSettingsController by nativeSettingsControllerDelegate

    private val nativeScanControllerDelegate = lazy {
        createNativeScanController().also { if (closed) it.clear() }
    }
    private val nativeScanController by nativeScanControllerDelegate

    private val nativeRecentControllerDelegate = lazy {
        createNativeRecentController().also { if (closed) it.clear() }
    }
    private val nativeRecentController by nativeRecentControllerDelegate

    private val nativeHubControllerDelegate = lazy {
        NativePaymentHubController(
            repository = runtime.paymentHubRepository,
            host = runtime.paymentHub,
            languageChanges = runtime.languageRepository.preference,
            currencyCodes = runtime.currencyPreferences.code
        ).also { if (closed) it.clear() }
    }
    private val nativeHubController by nativeHubControllerDelegate

    private val nativeOnboardingControllerDelegate = lazy {
        SparkNativeOnboardingController(
            onboarding = runtime.onboardingViewModel,
            walletAccess = runtime.walletAccess,
            languageChanges = runtime.languageRepository.preference,
            appName = configuration.appName,
            connectionOnly = runtime.onboardingCompleted.value,
            onComplete = runtime::completeOnboarding,
            canCancelSetup = ::canCancelSetup,
            onCancelSetup = onCancelSetup,
            privacyPolicyUrl = configuration.legalLinks.privacyPolicyUrl,
            termsUrl = configuration.legalLinks.termsUrl
        ).also { if (closed) it.clear() }
    }
    private val nativeOnboardingController by nativeOnboardingControllerDelegate

    private val nativeWalletSettingsControllerDelegate = lazy {
        SparkNativeWalletSettingsController(
            walletAccess = runtime.walletAccess,
            languageChanges = runtime.languageRepository.preference,
            isSubmitting = runtime.paymentCoordinator.isSubmitting,
            appName = configuration.appName
        ).also { if (closed) it.clear() }
    }
    private val nativeWalletSettingsController by nativeWalletSettingsControllerDelegate

    fun isConnected(): Boolean =
        !closed && runtime.walletAccess.state.value == WalletAccessState.Connected

    fun canCancelSetup(): Boolean = runtime.canCancelSetup

    fun observeConnected(onChange: (Boolean) -> Unit): () -> Unit {
        val job = observerScope.launch {
            runtime.walletAccess.state.collect { onChange(isConnected()) }
        }
        return { job.cancel() }
    }

    fun observeCanCancelSetup(onChange: (Boolean) -> Unit): () -> Unit {
        val job = observerScope.launch {
            combine(runtime.walletAccess.state, runtime.removed) { _, _ -> runtime.canCancelSetup }
                .collect(onChange)
        }
        return { job.cancel() }
    }

    fun observeRemoved(onChange: (Boolean) -> Unit): () -> Unit {
        val job = observerScope.launch { runtime.removed.collect(onChange) }
        return { job.cancel() }
    }

    /** Terminal disposal preserves credentials and can be retried if SDK cleanup fails. */
    @Throws(Exception::class)
    suspend fun close() {
        clearControllers()
        runtime.close()
    }

    fun clear() {
        clearControllers()
        runtime.clear()
    }

    private fun clearControllers() {
        if (closed) return
        closed = true
        observerScope.cancel()
        if (nativeSettingsControllerDelegate.isInitialized()) nativeSettingsController.clear()
        if (nativeScanControllerDelegate.isInitialized()) nativeScanController.clear()
        if (nativeRecentControllerDelegate.isInitialized()) nativeRecentController.clear()
        if (nativeHubControllerDelegate.isInitialized()) nativeHubController.clear()
        if (nativeOnboardingControllerDelegate.isInitialized()) nativeOnboardingController.clear()
        if (nativeWalletSettingsControllerDelegate.isInitialized()) {
            nativeWalletSettingsController.clear()
        }
    }

    /** One of `loading`, `onboarding`, or `tabs`. */
    fun stage(): String = if (closed) {
        "loading"
    } else {
        runtime.walletAccess.state.value.toStage(
            runtime.onboardingCompleted.value
        )
    }

    fun observeStage(onChange: (String) -> Unit): () -> Unit {
        val job =
            observerScope.launch {
                combine(runtime.walletAccess.state, runtime.onboardingCompleted) {
                        access,
                        completed
                    ->
                    access.toStage(completed)
                }.collect(onChange)
            }
        return { job.cancel() }
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
        if (!closed && !runtime.paymentCoordinator.isSubmitting.value) {
            runtime.tabState.select(AppTab.fromStoredValue(tab))
        }
    }

    fun settingsController(): NativeSettingsController = nativeSettingsController

    fun scanController(): NativePaymentScanController = nativeScanController

    fun recentController(): NativePaymentRecentController = nativeRecentController

    fun hubController(): NativePaymentHubController = nativeHubController

    fun onboardingController(): SparkNativeOnboardingController = nativeOnboardingController

    fun walletSettingsController(): SparkNativeWalletSettingsController =
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
                    receiptFeeInformation = null
                )
            }.collect {}
        }
        return controller
    }
}

private fun WalletAccessState.toStage(completed: Boolean): String = when (this) {
    WalletAccessState.Connected -> if (completed) "tabs" else "onboarding"
    WalletAccessState.Loading -> "loading"
    else -> "onboarding"
}
