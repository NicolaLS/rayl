@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package xyz.lilsus.raylsuite.feature.paymentsettings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.feature.currencysettings.DefaultCurrencyPreferences

class PaymentSettingsViewModelTest {
    @Test
    fun thresholdIsPreviewedInTheDisplayCurrency() = runTest {
        // The preview is what users read when choosing how much Rayl may pay without asking.
        val paymentPreferences = DefaultPaymentPreferencesRepository(MapSettings())
        val currencyPreferences = DefaultCurrencyPreferences(MapSettings())
        currencyPreferences.setCode("USD")
        val viewModel =
            PaymentSettingsViewModel(
                paymentPreferences = paymentPreferences,
                currencyPreferences = currencyPreferences,
                bitcoinPriceProvider = { 60_000.0 },
                dispatcher = StandardTestDispatcher(testScheduler)
            )
        advanceUntilIdle()

        // 10,000 sats = 0.0001 BTC = $6.00 at $60,000 per bitcoin.
        assertEquals(
            DisplayAmount(600L, DisplayCurrency.Fiat("USD")),
            viewModel.uiState.value.thresholdCurrencyEquivalent
        )

        viewModel.updateConfirmationThreshold(20_000L)
        advanceUntilIdle()

        assertEquals(20_000L, paymentPreferences.current().thresholdSats)
        assertEquals(
            DisplayAmount(1_200L, DisplayCurrency.Fiat("USD")),
            viewModel.uiState.value.thresholdCurrencyEquivalent
        )

        viewModel.clear()
    }
}
