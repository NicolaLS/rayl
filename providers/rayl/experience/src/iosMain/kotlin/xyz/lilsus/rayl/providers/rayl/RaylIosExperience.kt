package xyz.lilsus.rayl.providers.rayl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketStatus
import xyz.lilsus.rayl.providers.rayl.integration.pocket.RaylPasskeyAuthenticator
import xyz.lilsus.rayl.providers.rayl.integration.pocket.createIosPocketSetup
import xyz.lilsus.rayl.providers.rayl.integration.pocket.createIosRaylPocket
import xyz.lilsus.rayl.providers.rayl.integration.pocket.readIosPocketRecoveryPhrase
import xyz.lilsus.raylsuite.core.settings.createAppSettings
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
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletCardChange
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletCardsHost

/** Shown in place of the tabs while the pocket is not usable. */
data class RaylProblemSnapshot(val message: String, val retryTitle: String)

/**
 * The Kotlin side of the native iOS shell of Rayl's built-in wallet. Swift owns the `TabView`; this
 * owns the app scope so every tab's view controller shares one runtime. Choosing another wallet in
 * [cards] first closes the pocket session; [observeUseWallet] then reports the change.
 */
class RaylIosExperience(
    configuration: RaylExperienceConfiguration,
    environment: RaylEnvironment,
    breezApiKey: String?,
    /** The device's passkeys, or `null` for the stand-in passkey of automated builds. */
    passkeys: RaylPasskeyAuthenticator?,
    debugTools: Boolean,
    private val cards: WalletCardsHost
) {
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val runtime = RaylRuntime(
        appSettings = createAppSettings(),
        configuration = configuration,
        debugTools = debugTools,
        pocket = RaylEarlyStart.take { createIosRaylPocket(environment.pocketConfig(breezApiKey)) },
        createSetup = { createIosPocketSetup(environment.pocketConfig(breezApiKey), it, passkeys) },
        haptics = createHapticFeedbackManager()
    )

    init {
        // Back from adding a card reopens Settings, where it was started.
        if (cards.reopensAddCard()) runtime.tabState.select(AppTab.Settings)
    }

    private val settingsController = lazy {
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
    private val scanController = lazy { createScanController(configuration.appName) }
    private val recentController = lazy { createRecentController() }
    private val hubController = lazy {
        NativePaymentHubController(
            repository = runtime.paymentHubRepository,
            host = runtime.paymentHub,
            languageChanges = runtime.languageRepository.preference,
            currencyCodes = runtime.currencyPreferences.code
        )
    }
    private val onboardingController = lazy {
        RaylNativeOnboardingController(runtime)
    }
    init {
        // A Spark card with the pocket's wallet is then turned back.
        observerScope.launch {
            runtime.pocket.identity.filterNotNull().collect { cards.identified("spark:$it") }
        }
    }

    private val walletSettingsController = lazy {
        RaylNativeWalletSettingsController(runtime)
    }
    private val cardsController = lazy {
        NativeWalletCardsController(
            host = cards,
            busy = runtime.paymentCoordinator.isSubmitting,
            languageChanges = runtime.languageRepository.preference,
            onChange = { runtime.useWallet(it.encode()) },
            // The pocket is never removed.
            onRemovePaying = {}
        )
    }

    /**
     * `onboarding` until Rayl's wallet was introduced, then `tabs`: the pocket connects in the
     * background, so the camera never waits for it.
     */
    fun stage(): String = stageOf(runtime.exit.value, runtime.onboardingState.step.value)

    fun observeStage(onChange: (String) -> Unit): () -> Unit =
        observe(combine(runtime.exit, runtime.onboardingState.step, ::stageOf)) { onChange(it) }

    /** Shown when closing the pocket for another wallet failed. */
    fun observeProblem(onChange: (RaylProblemSnapshot) -> Unit): () -> Unit =
        observe(runtime.languageRepository.preference) {
            onChange(RaylProblemSnapshot(text("rayl_leave_failed"), text("rayl_retry")))
        }

    fun retry() = runtime.retryLeave()

    fun isConnected(): Boolean = connected(runtime.pocket.status.value, runtime.exit.value)

    fun observeConnected(onChange: (Boolean) -> Unit): () -> Unit = observe(
        combine(runtime.pocket.status, runtime.exit, ::connected)
    ) { onChange(it) }

    /** Reports the change of [cards] the user chose, once the pocket session has closed. */
    fun observeUseWallet(onChange: (WalletCardChange) -> Unit): () -> Unit = observe(runtime.exit) {
        val target = runtime.exitTarget
        if (it == RaylExit.Done && target is RaylExitTarget.Wallet) {
            WalletCardChange.decode(target.id)?.let(onChange)
        }
    }

    /** The shared "Pay with" screens at the top of Settings. */
    fun cardsController(): NativeWalletCardsController = cardsController.value

    /** Called once a debug build erased the pocket, so the host mounts a new one. */
    fun observeErased(onErased: () -> Unit): () -> Unit = observe(runtime.exit) {
        if (it == RaylExit.Done && runtime.exitTarget == RaylExitTarget.Erased) onErased()
    }

    fun selectedTab(): String = runtime.tabState.selectedTab.value.storedValue

    fun observeSelectedTab(onChange: (String) -> Unit): () -> Unit =
        observe(runtime.tabState.selectedTab) { onChange(it.storedValue) }

    fun observeTheme(onChange: (String) -> Unit): () -> Unit =
        observe(runtime.themePreferences.preference) { onChange(it.nativeColorSchemeValue()) }

    fun observeTabTitles(onChange: (Map<String, String>) -> Unit): () -> Unit =
        observe(runtime.languageRepository.preference) { onChange(appTabTitles()) }

    fun selectTab(tab: String) {
        if (!runtime.paymentCoordinator.isSubmitting.value) {
            runtime.tabState.select(AppTab.fromStoredValue(tab))
        }
    }

    fun settingsController(): NativeSettingsController = settingsController.value

    fun scanController(): NativePaymentScanController = scanController.value

    fun recentController(): NativePaymentRecentController = recentController.value

    fun hubController(): NativePaymentHubController = hubController.value

    fun onboardingController(): RaylNativeOnboardingController = onboardingController.value

    fun walletSettingsController(): RaylNativeWalletSettingsController =
        walletSettingsController.value

    fun clear() {
        if (settingsController.isInitialized()) settingsController.value.clear()
        if (scanController.isInitialized()) scanController.value.clear()
        if (recentController.isInitialized()) recentController.value.clear()
        if (hubController.isInitialized()) hubController.value.clear()
        if (walletSettingsController.isInitialized()) walletSettingsController.value.clear()
        if (cardsController.isInitialized()) cardsController.value.clear()
        if (onboardingController.isInitialized()) onboardingController.value.clear()
        observerScope.coroutineContext[Job]?.cancel()
        runtime.clear()
    }

    private fun <T> observe(flow: Flow<T>, onChange: suspend (T) -> Unit): () -> Unit {
        val job = observerScope.launch { flow.collect(onChange) }
        return { job.cancel() }
    }

    private fun createScanController(appName: String): NativePaymentScanController {
        val coordinator = runtime.paymentCoordinator
        val controller = NativePaymentScanController(
            onPaymentIntent = coordinator::dispatch,
            onHubIntent = runtime.paymentHub::dispatch
        )
        observerScope.launch {
            combine(
                coordinator.uiState,
                coordinator.sessionTransactions,
                coordinator.newSessionTransactionCount,
                runtime.paymentHub.state,
                runtime.languageRepository.preference
            ) { payment, transactions, newTransactionCount, hubState, _ ->
                controller.update(
                    payment = payment.toNativePaymentScreenState(),
                    appTitle = appName,
                    savePrompt = hubState.savePrompt,
                    recentCount = transactions.size,
                    newRecentCount = newTransactionCount
                )
            }.collect {}
        }
        observerScope.launch {
            coordinator.events.collect { event ->
                controller.emitMessage(
                    when (event) {
                        is PaymentEvent.ShowError -> paymentErrorMessage(event.error)
                        is PaymentEvent.ShowToast -> event.message.localizedMessage()
                    }
                )
            }
        }
        observerScope.launch {
            coordinator.transactionDetailNavigationTarget.collect { id ->
                if (id == null) return@collect
                runtime.tabState.openTransaction(id)
                coordinator.dispatch(PaymentIntent.TransactionDetailNavigationHandled(id))
            }
        }
        return controller
    }

    private fun createRecentController(): NativePaymentRecentController {
        val coordinator = runtime.paymentCoordinator
        val controller = NativePaymentRecentController(
            onIntent = coordinator::dispatch,
            onSelectTransaction = { id ->
                if (!coordinator.isSubmitting.value) runtime.tabState.selectTransaction(id)
            }
        )
        observerScope.launch {
            combine(
                coordinator.sessionTransactions,
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

private fun stageOf(exit: RaylExit, onboarding: RaylOnboardingStep): String = when (exit) {
    RaylExit.None -> if (onboarding == RaylOnboardingStep.Complete) "tabs" else "onboarding"
    RaylExit.Failed -> "problem"
    RaylExit.Working, RaylExit.Done -> "loading"
}

private fun connected(status: PocketStatus, exit: RaylExit): Boolean =
    status == PocketStatus.Ready && exit == RaylExit.None

internal fun text(key: String): String = nativeString(NativeStringResource("RaylExperience", key))

/** The pocket's recovery phrase while any wallet pays; see `WalletCardsHost.recoveryPhrase`. */
suspend fun raylRecoveryPhrase(environment: RaylEnvironment): String? =
    readIosPocketRecoveryPhrase(environment.pocketConfig(breezApiKey = null))

/** Call at app launch when Rayl's wallet is in use. */
fun startRaylPocketEarly(environment: RaylEnvironment, breezApiKey: String?) {
    RaylEarlyStart.start { createIosRaylPocket(environment.pocketConfig(breezApiKey)) }
}
