package xyz.lilsus.rayl.providers.blink

import com.russhwolf.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.blink.feature.blinkcontacts.BlinkContactsImporter
import xyz.lilsus.rayl.providers.blink.feature.payment.BlinkPaymentSettlement
import xyz.lilsus.rayl.providers.blink.integration.blink.createBlinkWallet
import xyz.lilsus.raylsuite.core.model.WalletStartMode
import xyz.lilsus.raylsuite.core.network.createNetworkConnectivity
import xyz.lilsus.raylsuite.core.settings.ConnectionStorageReset
import xyz.lilsus.raylsuite.core.settings.SecureStringStore
import xyz.lilsus.raylsuite.core.ui.platform.HapticFeedbackManager
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
import xyz.lilsus.raylsuite.integration.exchangerate.RaylBitcoinPriceProvider
import xyz.lilsus.raylsuite.integration.lnurl.KtorLnurlPayClient

internal class BlinkRuntime(
    private val appSettings: Settings,
    private val walletSettings: Settings,
    val configuration: BlinkExperienceConfiguration,
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
    private var recoveryFailed = false
    private val recoveredRemoval = try {
        storageReset.resume()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        recoveryFailed = true
        false
    }

    private val mutableRemoved = MutableStateFlow(recoveredRemoval)
    val removed: StateFlow<Boolean> = mutableRemoved
    private val mutableRemovalPending = MutableStateFlow(recoveryFailed)
    val removalPending: StateFlow<Boolean> = mutableRemovalPending
    private val mutableRemoving = MutableStateFlow(false)
    val isRemoving: StateFlow<Boolean> = mutableRemoving
    private var removing = false
    private var closed = false
    private var runtimeStarted = false
    val hasStarted: Boolean get() = runtimeStarted
    private val mutableConnected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = mutableConnected
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val themePreferences = DefaultThemePreferences(appSettings)
    val currencyPreferences = DefaultCurrencyPreferences(appSettings)
    val languageRepository = createLanguageRepository()
    val paymentPreferences = DefaultPaymentPreferencesRepository(appSettings)
    val paymentHubRepository = DefaultPaymentHubRepository(appSettings)
    val tabState = AppTabState()
    val paymentHub = PaymentHubController(paymentHubRepository, scope)

    private val networkConnectivity by lazy { createNetworkConnectivity() }
    private val blinkWalletDelegate = lazy {
        createBlinkWallet(
            secureSettings = secureSettings,
            startMode = startMode,
            isNetworkAvailable = networkConnectivity::isNetworkAvailable
        )
    }
    val blinkWallet by blinkWalletDelegate

    private val bitcoinPriceProviderDelegate = lazy { RaylBitcoinPriceProvider() }
    val bitcoinPriceProvider by bitcoinPriceProviderDelegate
    private val lnurlPayClientDelegate = lazy { KtorLnurlPayClient(networkConnectivity) }
    private val lnurlPayClient by lnurlPayClientDelegate

    private val paymentCoordinatorDelegate = lazy {
        PaymentFlowCoordinator(
            settlement = BlinkPaymentSettlement(blinkWallet, bitcoinPriceProvider),
            lnurlPayClient = lnurlPayClient,
            bitcoinPriceProvider = bitcoinPriceProvider,
            currencyPreferences = currencyPreferences,
            paymentPreferences = paymentPreferences,
            paymentHub = paymentHub,
            haptics = haptics
        )
    }
    val paymentCoordinator by paymentCoordinatorDelegate

    private val onboardingViewModelDelegate = lazy {
        OnboardingViewModel(
            paymentPreferences = paymentPreferences,
            currencyPreferences = currencyPreferences,
            bitcoinPriceProvider = bitcoinPriceProvider
        )
    }
    val onboardingViewModel by onboardingViewModelDelegate

    val onboardingState = BlinkOnboardingState(appSettings)
    val canConnect: Boolean get() = !closed && !mutableRemovalPending.value && !mutableRemoved.value

    init {
        if (!recoveryFailed && !recoveredRemoval) start()
    }

    private fun start() {
        runtimeStarted = true
        var wasConnected = blinkWallet.connection.value != null
        mutableConnected.value = wasConnected
        scope.launch {
            blinkWallet.connection.collect { connection ->
                val isConnected = connection != null
                mutableConnected.value = isConnected
                if (isConnected && !wasConnected) {
                    launch { importBlinkContacts() }
                }
                wasConnected = isConnected
            }
        }
        scope.launch {
            // A payment always presents on the Scan tab, wherever it was started from.
            paymentCoordinator.uiState.collect { state ->
                if (state != PaymentUiState.Active) tabState.requestScan()
            }
        }
    }

    private suspend fun importBlinkContacts() {
        try {
            BlinkContactsImporter(blinkWallet, paymentHubRepository).importAll()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Contact sync is helpful, but must not turn a valid wallet connection into a failure.
        }
    }

    fun resetPaymentSession() {
        paymentCoordinator.resetSession()
    }

    fun removeWallet() {
        if (removing || (runtimeStarted && paymentCoordinator.isSubmitting.value)) return
        removing = true
        mutableRemoving.value = true
        mutableRemovalPending.value = true
        try {
            storageReset.begin()
            if (runtimeStarted) {
                paymentCoordinator.resetSession()
                paymentCoordinator.clear()
                blinkWallet.close()
                blinkWallet.disconnect()
            }
            storageReset.finish()
            mutableRemoved.value = true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Keep the experience blocked until retry or restart finishes erasure.
        } finally {
            removing = false
            mutableRemoving.value = false
        }
    }

    fun clear() {
        if (closed) return
        closed = true
        if (onboardingViewModelDelegate.isInitialized()) onboardingViewModel.clear()
        if (paymentCoordinatorDelegate.isInitialized()) paymentCoordinator.clear()
        if (bitcoinPriceProviderDelegate.isInitialized()) bitcoinPriceProvider.close()
        if (lnurlPayClientDelegate.isInitialized()) lnurlPayClient.close()
        if (blinkWalletDelegate.isInitialized()) blinkWallet.close()
        languageRepository.close()
        scope.cancel()
    }
}
