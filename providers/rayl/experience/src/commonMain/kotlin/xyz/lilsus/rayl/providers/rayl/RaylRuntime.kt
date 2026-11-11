package xyz.lilsus.rayl.providers.rayl

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.rayl.feature.payment.RaylPaymentSettlement
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketSetup
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketStatus
import xyz.lilsus.rayl.providers.rayl.integration.pocket.RaylPocket
import xyz.lilsus.raylsuite.core.network.createNetworkConnectivity
import xyz.lilsus.raylsuite.core.raylapi.TelemetryOnboardingStep
import xyz.lilsus.raylsuite.core.telemetry.RaylTrace
import xyz.lilsus.raylsuite.core.telemetry.Telemetry
import xyz.lilsus.raylsuite.core.ui.platform.HapticFeedbackManager
import xyz.lilsus.raylsuite.feature.appshell.AppTabState
import xyz.lilsus.raylsuite.feature.currencysettings.DefaultCurrencyPreferences
import xyz.lilsus.raylsuite.feature.languagesettings.createLanguageRepository
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentFlowCoordinator
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentUiState
import xyz.lilsus.raylsuite.feature.paymenthub.DefaultPaymentHubRepository
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController
import xyz.lilsus.raylsuite.feature.paymentsettings.DefaultPaymentPreferencesRepository
import xyz.lilsus.raylsuite.feature.themesettings.DefaultThemePreferences
import xyz.lilsus.raylsuite.integration.exchangerate.RaylBitcoinPriceProvider
import xyz.lilsus.raylsuite.integration.lnurl.KtorLnurlPayClient

/**
 * Objects of Rayl's built-in wallet experience, held outside composition so both the Compose host
 * and the native iOS shell share one instance. The pocket starts as soon as it mounts; only its
 * first use needs a passkey, set up through [createSetup] during onboarding.
 */
internal class RaylRuntime(
    appSettings: Settings,
    val configuration: RaylExperienceConfiguration,
    /** A debug build: offers erasing the pocket's key copy, whichever network it uses. */
    debugTools: Boolean,
    val pocket: RaylPocket,
    createSetup: (RaylPocket) -> PocketSetup,
    haptics: HapticFeedbackManager,
    onFundingInvoice: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val networkConnectivity by lazy { createNetworkConnectivity() }
    private var closed = false

    val themePreferences = DefaultThemePreferences(appSettings)
    val currencyPreferences = DefaultCurrencyPreferences(appSettings)
    val languageRepository = createLanguageRepository()
    val paymentPreferences = DefaultPaymentPreferencesRepository(appSettings)
    val paymentHubRepository = DefaultPaymentHubRepository(appSettings)
    val tabState = AppTabState()
    val bitcoinPriceProvider = RaylBitcoinPriceProvider()
    val paymentHub = PaymentHubController(
        repository = paymentHubRepository,
        scope = scope,
        prices = bitcoinPriceProvider,
        priceHistory = bitcoinPriceProvider
    )
    private val lnurlPayClient = KtorLnurlPayClient(networkConnectivity)
    val paymentCoordinator = PaymentFlowCoordinator(
        settlement = RaylPaymentSettlement(pocket),
        lnurlPayClient = lnurlPayClient,
        bitcoinPriceProvider = bitcoinPriceProvider,
        currencyPreferences = currencyPreferences,
        paymentPreferences = paymentPreferences,
        paymentHub = paymentHub,
        haptics = haptics
    )
    val onboardingState = RaylOnboardingState(appSettings, scope) { createSetup(pocket) }
    val funding = RaylFunding(
        createInvoice = { pocket.createInvoice(amountSats = null, description = it) },
        receipts = pocket.receivedPayments,
        description = configuration.appName,
        scope = scope,
        onInvoice = onFundingInvoice
    )

    private val walletExit = RaylWalletExit(
        scope = scope,
        canErase = debugTools,
        keyUnreadable = { pocket.status.value == PocketStatus.KeyUnreadable },
        isBusy = { closed || paymentCoordinator.isSubmitting.value },
        stopSession = {
            paymentCoordinator.resetSession()
            funding.reset()
        },
        closePocket = { pocket.close() },
        erasePocket = { pocket.erase().also { erased -> if (erased) onboardingState.reset() } }
    )
    val exit: StateFlow<RaylExit> = walletExit.state
    val canErase: Boolean = walletExit.canErase

    /** Where the host goes once [exit] is [RaylExit.Done]. */
    val exitTarget: RaylExitTarget? get() = walletExit.target

    init {
        RaylTrace.debug("experience created")
        pocket.start()
        // Onboarding steps for usage data; each is reported once per installation.
        scope.launch {
            pocket.status.first { it == PocketStatus.Ready }
            Telemetry.onboarding(TelemetryOnboardingStep.WalletReady)
        }
        scope.launch {
            pocket.balanceSats.first { it != null && it > 0 }
            Telemetry.onboarding(TelemetryOnboardingStep.Funded)
        }
        scope.launch {
            // A payment always presents on the Scan tab, wherever it was started from.
            paymentCoordinator.uiState.collect { state ->
                if (state != PaymentUiState.Active) tabState.requestScan()
            }
        }
    }

    fun retryPocket() {
        if (exit.value == RaylExit.None) pocket.start()
    }

    /** See [RaylWalletExit.useWallet]. */
    fun useWallet(walletId: String): Boolean = walletExit.useWallet(walletId)

    /** See [RaylWalletExit.erase]. */
    fun erase() = walletExit.erase()

    /** See [RaylWalletExit.setUpAgain] and SPEC-wallet-lifecycle. */
    fun setUpAgain() = walletExit.setUpAgain()

    fun retryLeave() = walletExit.retry()

    /** Best-effort platform teardown; [leave] awaits the pocket before the host moves on. */
    fun clear() {
        if (closed) return
        closed = true
        scope.cancel()
        paymentCoordinator.clear()
        onboardingState.close()
        bitcoinPriceProvider.close()
        lnurlPayClient.close()
        languageRepository.close()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch(
            start = CoroutineStart.UNDISPATCHED
        ) {
            runCatching { pocket.close() }
        }
    }
}
