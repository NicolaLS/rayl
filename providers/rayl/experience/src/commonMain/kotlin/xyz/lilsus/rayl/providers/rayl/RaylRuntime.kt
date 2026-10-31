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
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketTimings
import xyz.lilsus.rayl.providers.rayl.integration.pocket.RaylPocket
import xyz.lilsus.raylsuite.core.network.createNetworkConnectivity
import xyz.lilsus.raylsuite.core.ui.platform.HapticFeedbackManager
import xyz.lilsus.raylsuite.feature.appshell.AppTab
import xyz.lilsus.raylsuite.feature.appshell.AppTabState
import xyz.lilsus.raylsuite.feature.currencysettings.DefaultCurrencyPreferences
import xyz.lilsus.raylsuite.feature.languagesettings.createLanguageRepository
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingViewModel
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentFlowCoordinator
import xyz.lilsus.raylsuite.feature.paymentflow.PaymentUiState
import xyz.lilsus.raylsuite.feature.paymenthub.DefaultPaymentHubRepository
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController
import xyz.lilsus.raylsuite.feature.paymentsettings.DefaultPaymentPreferencesRepository
import xyz.lilsus.raylsuite.feature.paymentui.PaymentIntent
import xyz.lilsus.raylsuite.feature.themesettings.DefaultThemePreferences
import xyz.lilsus.raylsuite.integration.exchangerate.RaylBitcoinPriceProvider
import xyz.lilsus.raylsuite.integration.lnurl.KtorLnurlPayClient

internal enum class RaylExit { None, Working, Failed, Done }

internal sealed interface RaylExitTarget {
    /** The user chose an external wallet offered by the host. */
    data class Wallet(val id: String) : RaylExitTarget

    /** A debug build erased the wallet; the host mounts a new one. */
    data object Erased : RaylExitTarget
}

/**
 * Objects of Rayl's built-in wallet experience, held outside composition so both the Compose host
 * and the native iOS shell share one instance. There is no setup step: the pocket starts as soon as
 * it mounts.
 */
internal class RaylRuntime(
    appSettings: Settings,
    val configuration: RaylExperienceConfiguration,
    environment: RaylEnvironment,
    val pocket: RaylPocket,
    haptics: HapticFeedbackManager,
    onFundingInvoice: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val networkConnectivity by lazy { createNetworkConnectivity() }
    private var closed = false

    /** Where the host goes once [exit] is [RaylExit.Done]. */
    var exitTarget: RaylExitTarget? = null
        private set

    val canErase = environment == RaylEnvironment.DEBUG

    val themePreferences = DefaultThemePreferences(appSettings)
    val currencyPreferences = DefaultCurrencyPreferences(appSettings)
    val languageRepository = createLanguageRepository()
    val paymentPreferences = DefaultPaymentPreferencesRepository(appSettings)
    val paymentHubRepository = DefaultPaymentHubRepository(appSettings)
    val tabState = AppTabState()
    val paymentHub = PaymentHubController(paymentHubRepository, scope)
    val bitcoinPriceProvider = RaylBitcoinPriceProvider()
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
    val onboardingState = RaylOnboardingState(appSettings)
    val onboarding =
        OnboardingViewModel(paymentPreferences, currencyPreferences, bitcoinPriceProvider)
    val funding = RaylFunding(
        createInvoice = { pocket.createInvoice(amountSats = null, description = it) },
        receipts = pocket.receivedPayments,
        description = configuration.appName,
        scope = scope,
        onInvoice = onFundingInvoice
    )

    private val mutableExit = MutableStateFlow(RaylExit.None)
    val exit: StateFlow<RaylExit> = mutableExit.asStateFlow()

    init {
        PocketTimings.mark("experience created")
        pocket.start()
        scope.launch {
            // A link that arrives while the pocket connects waits in the settlement step.
            RaylPaymentLinks.events.collect { uri ->
                PocketTimings.mark("payment link received")
                if (funding.isOwnInvoice(uri)) {
                    // No other wallet took the funding invoice; Rayl must not pay itself.
                    funding.showInvoice()
                    tabState.select(AppTab.Settings)
                    return@collect
                }
                // Payments present on the Scan tab, which shows once onboarding is complete.
                onboardingState.step.first { it == RaylOnboardingStep.Complete }
                if (mutableExit.value == RaylExit.None && !paymentCoordinator.isSubmitting.value) {
                    tabState.requestScan()
                    paymentCoordinator.dispatch(PaymentIntent.DeepLinkReceived(uri))
                }
            }
        }
        scope.launch {
            // E.g. a wallet connection link opened while Rayl's wallet is in use.
            RaylWalletRequests.events.collect { walletId ->
                if (!useWallet(walletId)) RaylWalletRequests.rejected(walletId)
            }
        }
        scope.launch {
            // A payment always presents on the Scan tab, wherever it was started from.
            paymentCoordinator.uiState.collect { state ->
                if (state != PaymentUiState.Active) tabState.requestScan()
            }
        }
    }

    fun retryPocket() {
        if (mutableExit.value == RaylExit.None) pocket.start()
    }

    /**
     * Closes Rayl's wallet so the host can set up the external wallet [walletId]. Its key and funds
     * stay on this device, and the host returns here when the external wallet is removed.
     * Returns false while a payment is being submitted.
     */
    fun useWallet(walletId: String): Boolean = leave(RaylExitTarget.Wallet(walletId))

    /** Debug builds only: deletes the wallet key and its funds, then starts a new wallet. */
    fun erase() {
        if (canErase) leave(RaylExitTarget.Erased)
    }

    private fun leave(target: RaylExitTarget): Boolean {
        if (closed || paymentCoordinator.isSubmitting.value) return false
        if (mutableExit.value == RaylExit.Working ||
            mutableExit.value == RaylExit.Done
        ) {
            return false
        }
        exitTarget = target
        mutableExit.value = RaylExit.Working
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutableExit.value = try {
                paymentCoordinator.resetSession()
                funding.reset()
                val finished = if (target == RaylExitTarget.Erased) {
                    pocket.erase()
                } else {
                    pocket.close()
                    true
                }
                if (finished) RaylExit.Done else RaylExit.Failed
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                RaylExit.Failed
            }
        }
        return true
    }

    fun retryLeave() {
        val target = exitTarget
        if (mutableExit.value == RaylExit.Failed && target != null) {
            mutableExit.value = RaylExit.None
            leave(target)
        }
    }

    /** Best-effort platform teardown; [leave] awaits the pocket before the host moves on. */
    fun clear() {
        if (closed) return
        closed = true
        scope.cancel()
        paymentCoordinator.clear()
        onboarding.clear()
        bitcoinPriceProvider.close()
        lnurlPayClient.close()
        languageRepository.close()
        RaylPaymentLinks.clear()
        RaylWalletRequests.clear()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch(
            start = CoroutineStart.UNDISPATCHED
        ) {
            runCatching { pocket.close() }
        }
    }
}
