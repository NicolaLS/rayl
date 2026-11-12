package xyz.lilsus.rayl.providers.nwc

import com.russhwolf.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.nwc.feature.onboarding.NwcConnectionDraft
import xyz.lilsus.rayl.providers.nwc.feature.payment.NwcPaymentSettlement
import xyz.lilsus.rayl.providers.nwc.integration.nwc.createNwcWallet
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.WalletStartMode
import xyz.lilsus.raylsuite.core.network.createNetworkConnectivity
import xyz.lilsus.raylsuite.core.settings.ConnectionStorageReset
import xyz.lilsus.raylsuite.core.settings.SecureStringStore
import xyz.lilsus.raylsuite.core.ui.platform.HapticFeedbackManager
import xyz.lilsus.raylsuite.core.ui.platform.createAppLifecycleObserver
import xyz.lilsus.raylsuite.feature.appshell.AppTabState
import xyz.lilsus.raylsuite.feature.currencysettings.DefaultCurrencyPreferences
import xyz.lilsus.raylsuite.feature.languagesettings.createLanguageRepository
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingViewModel
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentFlowCoordinator
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentUiState
import xyz.lilsus.raylsuite.feature.paymenthub.DefaultPaymentHubRepository
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController
import xyz.lilsus.raylsuite.feature.paymentsettings.DefaultPaymentPreferencesRepository
import xyz.lilsus.raylsuite.feature.themesettings.DefaultThemePreferences
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletBalance
import xyz.lilsus.raylsuite.integration.exchangerate.RaylBitcoinPriceProvider
import xyz.lilsus.raylsuite.integration.lnurl.KtorLnurlPayClient

internal class NwcRuntime(
    appSettings: Settings,
    private val walletSettings: Settings,
    val configuration: NwcExperienceConfiguration,
    secureSettings: SecureStringStore,
    haptics: HapticFeedbackManager,
    startMode: WalletStartMode
) {
    private val storageReset = ConnectionStorageReset(
        appSettings,
        walletSettings,
        secureSettings,
        "${configuration.walletPreferencesName}.removal.pending"
    )
    private var startupRemovalFailed = false
    private val recoveredRemoval = try {
        storageReset.resume()
    } catch (_: Exception) {
        startupRemovalFailed = true
        false
    }

    private val mutableRemoved = MutableStateFlow(recoveredRemoval)
    val removed: StateFlow<Boolean> = mutableRemoved
    private val mutableRemovalPending = MutableStateFlow(startupRemovalFailed || recoveredRemoval)
    val removalPending: StateFlow<Boolean> = mutableRemovalPending
    private val mutableRemovalFailed = MutableStateFlow(startupRemovalFailed)
    val removalFailed: StateFlow<Boolean> = mutableRemovalFailed
    private var removing = false
    private var closed = false
    val canAcceptWalletInput: Boolean get() = !closed && !mutableRemovalPending.value
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val networkConnectivity by lazy { createNetworkConnectivity() }
    private val appLifecycle by lazy { createAppLifecycleObserver() }
    private val runtimeStarted = !mutableRemovalPending.value

    val themePreferences = DefaultThemePreferences(appSettings)
    val currencyPreferences = DefaultCurrencyPreferences(appSettings)
    val languageRepository = createLanguageRepository()
    val paymentPreferences = DefaultPaymentPreferencesRepository(appSettings)
    val paymentHubRepository = DefaultPaymentHubRepository(appSettings)
    val tabState = AppTabState()

    /** Connecting another wallet from the Settings tab. */
    private val mutableSettingsWalletFlow = MutableStateFlow(false)
    val settingsWalletFlow: StateFlow<Boolean> = mutableSettingsWalletFlow.asStateFlow()
    val bitcoinPriceProvider = RaylBitcoinPriceProvider()
    val paymentHub = PaymentHubController(
        repository = paymentHubRepository,
        scope = scope,
        prices = bitcoinPriceProvider,
        priceHistory = bitcoinPriceProvider
    )
    val connectionDraft = NwcConnectionDraft()
    val nwcWallet by lazy {
        createNwcWallet(
            secureSettings = secureSettings,
            startMode = startMode,
            scope = scope,
            isNetworkAvailable = networkConnectivity::isNetworkAvailable
        )
    }
    val onboardingState = NwcOnboardingState(appSettings)

    private val lnurlPayClient = KtorLnurlPayClient(networkConnectivity)
    val paymentCoordinator by lazy {
        PaymentFlowCoordinator(
            settlement = NwcPaymentSettlement(nwcWallet),
            lnurlPayClient = lnurlPayClient,
            bitcoinPriceProvider = bitcoinPriceProvider,
            currencyPreferences = currencyPreferences,
            paymentPreferences = paymentPreferences,
            paymentHub = paymentHub,
            haptics = haptics
        )
    }

    val onboardingViewModel =
        OnboardingViewModel(
            paymentPreferences = paymentPreferences,
            currencyPreferences = currencyPreferences,
            bitcoinPriceProvider = bitcoinPriceProvider
        )

    /** Shown in Settings; NWC cards are funded in their own wallet apps. */
    val balance = WalletBalance(scope) {
        nwcWallet.balanceSats()?.let { DisplayAmount(it, DisplayCurrency.Satoshi) }
    }

    init {
        if (runtimeStarted) {
            scope.launch {
                appLifecycle.isInForeground.collectLatest(nwcWallet::onAppForegroundChanged)
            }
            scope.launch {
                // Once connected, and after every payment.
                combine(nwcWallet.connection, paymentCoordinator.isSubmitting) {
                        connection,
                        submitting
                    ->
                    connection != null && !submitting
                }.collect { if (it) balance.refresh() }
            }
            scope.launch {
                // A payment always presents on the Scan tab, wherever it was started from.
                paymentCoordinator.uiState.collect { state ->
                    if (state != PaymentUiState.Active) tabState.requestScan()
                }
            }
        }
    }

    fun requestSettingsWalletFlow() {
        if (
            !canAcceptWalletInput ||
            (runtimeStarted && paymentCoordinator.isSubmitting.value)
        ) {
            return
        }
        mutableSettingsWalletFlow.value = true
    }

    fun walletFlowHandled() {
        mutableSettingsWalletFlow.value = false
    }

    fun completeOnboarding() {
        if (!canAcceptWalletInput) return
        onboardingState.complete()
    }

    fun removeWallet() {
        if (removing || (runtimeStarted && paymentCoordinator.isSubmitting.value)) return
        removing = true
        mutableRemovalPending.value = true
        mutableRemovalFailed.value = false
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                storageReset.begin()
                if (runtimeStarted) {
                    paymentCoordinator.resetSession()
                    paymentCoordinator.clear()
                }
                connectionDraft.clear()
                if (runtimeStarted) {
                    nwcWallet.close()
                    nwcWallet.disconnect()
                }
                storageReset.finish()
                mutableRemoved.value = true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableRemovalFailed.value = true
            } finally {
                removing = false
            }
        }
    }

    fun clear() {
        if (closed) return
        closed = true
        connectionDraft.clear()
        scope.cancel()
        onboardingViewModel.clear()
        if (runtimeStarted) paymentCoordinator.clear()
        bitcoinPriceProvider.close()
        lnurlPayClient.close()
        languageRepository.close()
        if (runtimeStarted) {
            appLifecycle.close()
            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch(
                start = CoroutineStart.UNDISPATCHED
            ) {
                nwcWallet.close()
            }
        }
    }
}
