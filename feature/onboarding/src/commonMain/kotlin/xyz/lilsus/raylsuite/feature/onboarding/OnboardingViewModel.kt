package xyz.lilsus.raylsuite.feature.onboarding

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.CurrencyInfo
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode
import xyz.lilsus.raylsuite.core.model.PaymentPreferences
import xyz.lilsus.raylsuite.core.model.convertMsatsToDisplayAmount
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider
import xyz.lilsus.raylsuite.feature.currencysettings.CurrencyPreferences
import xyz.lilsus.raylsuite.feature.paymentsettings.PaymentPreferencesRepository

data class OnboardingUiState(
    val confirmationMode: PaymentConfirmationMode = PaymentConfirmationMode.Above,
    val thresholdSats: Long = PaymentPreferences.DEFAULT_CONFIRMATION_THRESHOLD_SATS,
    val hasAgreed: Boolean = false,
    val thresholdCurrencyEquivalent: DisplayAmount? = null
)

class OnboardingViewModel(
    private val paymentPreferences: PaymentPreferencesRepository,
    currencyPreferences: CurrencyPreferences,
    private val bitcoinPriceProvider: BitcoinPriceProvider? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var selectedCurrency = CurrencyCatalog.infoFor(CurrencyCatalog.DEFAULT_CODE)
    private var pricePerBitcoin: Double? = null
    private var priceRequestId = 0

    private val mutableUiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = mutableUiState.asStateFlow()

    init {
        scope.launch {
            paymentPreferences.preferences.collect { preferences ->
                mutableUiState.update {
                    it.copy(
                        confirmationMode = preferences.confirmationMode,
                        thresholdSats = preferences.thresholdSats
                    )
                }
                publishThresholdPreview()
            }
        }
        scope.launch {
            currencyPreferences.code.collectLatest(::updateSelectedCurrency)
        }
    }

    fun setConfirmationMode(mode: PaymentConfirmationMode) {
        scope.launch { paymentPreferences.setConfirmationMode(mode) }
    }

    fun setThreshold(thresholdSats: Long) {
        val clampedThreshold =
            thresholdSats.coerceIn(
                PaymentPreferences.MIN_CONFIRMATION_THRESHOLD_SATS,
                PaymentPreferences.MAX_CONFIRMATION_THRESHOLD_SATS
            )
        scope.launch {
            paymentPreferences.setConfirmationThreshold(clampedThreshold)
        }
    }

    fun setAgreement(agreed: Boolean) {
        mutableUiState.update { it.copy(hasAgreed = agreed) }
    }

    fun clear() {
        scope.cancel()
    }

    private suspend fun updateSelectedCurrency(code: String) {
        val currency = CurrencyCatalog.infoFor(code)
        val requestId = ++priceRequestId
        selectedCurrency = currency
        pricePerBitcoin = null
        publishThresholdPreview()

        val fiat = currency.currency as? DisplayCurrency.Fiat ?: return
        val price = bitcoinPriceProvider?.pricePerBitcoin(fiat.iso4217) ?: return
        if (requestId != priceRequestId) return

        pricePerBitcoin = price.coerceAtLeast(0.0)
        publishThresholdPreview()
    }

    private fun publishThresholdPreview() {
        mutableUiState.update { state ->
            state.copy(
                thresholdCurrencyEquivalent =
                    thresholdDisplayAmount(
                        thresholdSats = state.thresholdSats,
                        currency = selectedCurrency,
                        pricePerBitcoin = pricePerBitcoin
                    )?.takeUnless { it.currency == DisplayCurrency.Satoshi }
            )
        }
    }
}

private fun thresholdDisplayAmount(
    thresholdSats: Long,
    currency: CurrencyInfo,
    pricePerBitcoin: Double?
): DisplayAmount? {
    if (thresholdSats < 0 || thresholdSats > Long.MAX_VALUE / MSATS_PER_SAT) return null
    return convertMsatsToDisplayAmount(
        msats = thresholdSats * MSATS_PER_SAT,
        info = currency,
        fiatPricePerBitcoin = pricePerBitcoin
    )
}

private const val MSATS_PER_SAT = 1_000L
