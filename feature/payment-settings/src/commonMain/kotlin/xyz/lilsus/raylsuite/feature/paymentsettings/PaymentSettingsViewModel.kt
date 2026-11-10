package xyz.lilsus.raylsuite.feature.paymentsettings

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.CurrencyInfo
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode
import xyz.lilsus.raylsuite.core.model.PaymentPreferences
import xyz.lilsus.raylsuite.core.model.convertMsatsToDisplayAmount
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider
import xyz.lilsus.raylsuite.core.raylapi.TelemetrySetting
import xyz.lilsus.raylsuite.core.telemetry.Telemetry
import xyz.lilsus.raylsuite.feature.currencysettings.CurrencyPreferences

class PaymentSettingsViewModel(
    private val paymentPreferences: PaymentPreferencesRepository,
    currencyPreferences: CurrencyPreferences,
    private val bitcoinPriceProvider: BitcoinPriceProvider,
    dispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var selectedCurrency = CurrencyCatalog.infoFor(CurrencyCatalog.DEFAULT_CODE)
    private var pricePerBitcoin: Double? = null
    private var priceRequestId = 0

    private val mutableUiState = MutableStateFlow(PaymentSettingsUiState())
    val uiState: StateFlow<PaymentSettingsUiState> = mutableUiState.asStateFlow()

    init {
        scope.launch {
            paymentPreferences.preferences.collectLatest { preferences ->
                mutableUiState.value =
                    mutableUiState.value.copy(
                        confirmationMode = preferences.confirmationMode,
                        thresholdSats = preferences.thresholdSats,
                        offerToSaveNewTargets = preferences.offerToSaveNewTargets,
                        vibrateOnScan = preferences.vibrateOnScan,
                        vibrateOnPayment = preferences.vibrateOnPayment
                    )
                publishThresholdPreview()
            }
        }
        scope.launch {
            currencyPreferences.code.collectLatest(::updateSelectedCurrency)
        }
    }

    fun selectConfirmationMode(mode: PaymentConfirmationMode) {
        Telemetry.settingChanged(
            when (mode) {
                PaymentConfirmationMode.Always -> TelemetrySetting.ConfirmationAlways
                PaymentConfirmationMode.Above -> TelemetrySetting.ConfirmationThreshold
            }
        )
        scope.launch {
            paymentPreferences.setConfirmationMode(mode)
        }
    }

    fun updateConfirmationThreshold(thresholdSats: Long) {
        Telemetry.settingChanged(TelemetrySetting.ConfirmationAmount)
        scope.launch {
            paymentPreferences.setConfirmationThreshold(thresholdSats)
        }
    }

    fun setOfferToSaveNewTargets(enabled: Boolean) {
        Telemetry.settingChanged(TelemetrySetting.OfferToSaveTargets)
        scope.launch {
            paymentPreferences.setOfferToSaveNewTargets(enabled)
        }
    }

    fun setVibrateOnScan(enabled: Boolean) {
        Telemetry.settingChanged(TelemetrySetting.Haptics)
        scope.launch {
            paymentPreferences.setVibrateOnScan(enabled)
        }
    }

    fun setVibrateOnPayment(enabled: Boolean) {
        Telemetry.settingChanged(TelemetrySetting.Haptics)
        scope.launch {
            paymentPreferences.setVibrateOnPayment(enabled)
        }
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
        val price = bitcoinPriceProvider.pricePerBitcoin(fiat.iso4217)
        if (requestId != priceRequestId) return

        pricePerBitcoin = price?.coerceAtLeast(0.0)
        publishThresholdPreview()
    }

    private fun publishThresholdPreview() {
        mutableUiState.value =
            mutableUiState.value.copy(
                thresholdCurrencyEquivalent =
                    thresholdDisplayAmount(
                        thresholdSats = mutableUiState.value.thresholdSats,
                        currency = selectedCurrency,
                        pricePerBitcoin = pricePerBitcoin
                    )?.takeUnless { it.currency == DisplayCurrency.Satoshi }
            )
    }
}

data class PaymentSettingsUiState(
    val confirmationMode: PaymentConfirmationMode = PaymentPreferences().confirmationMode,
    val thresholdSats: Long = PaymentPreferences.DEFAULT_CONFIRMATION_THRESHOLD_SATS,
    val offerToSaveNewTargets: Boolean = PaymentPreferences().offerToSaveNewTargets,
    val vibrateOnScan: Boolean = PaymentPreferences().vibrateOnScan,
    val vibrateOnPayment: Boolean = PaymentPreferences().vibrateOnPayment,
    val thresholdCurrencyEquivalent: DisplayAmount? = null
)

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
