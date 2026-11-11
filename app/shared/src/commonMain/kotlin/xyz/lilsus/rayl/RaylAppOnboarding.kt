package xyz.lilsus.rayl

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.lilsus.raylsuite.core.raylapi.TelemetryOnboardingStep
import xyz.lilsus.raylsuite.core.telemetry.Telemetry
import xyz.lilsus.raylsuite.feature.currencysettings.DefaultCurrencyPreferences
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingViewModel
import xyz.lilsus.raylsuite.feature.paymentsettings.DefaultPaymentPreferencesRepository
import xyz.lilsus.raylsuite.integration.exchangerate.RaylBitcoinPriceProvider

/**
 * Rayl's own onboarding, before any wallet: the welcome, which says what Rayl does, and Auto Pay.
 * Wallets only onboard what they need themselves, such as accepting their terms.
 */
internal enum class AppOnboardingStep { Welcome, AutoPay, Complete }

/** Persists completion only; an interrupted onboarding starts over at the welcome. */
internal class RaylAppOnboarding(private val settings: Settings) {
    private val mutableStep = MutableStateFlow(
        if (appOnboarded(settings)) AppOnboardingStep.Complete else AppOnboardingStep.Welcome
    )
    val step = mutableStep.asStateFlow()

    init {
        if (mutableStep.value == AppOnboardingStep.Welcome) {
            Telemetry.onboarding(TelemetryOnboardingStep.Welcome)
        }
    }

    fun moveTo(step: AppOnboardingStep) {
        if (mutableStep.value == AppOnboardingStep.Complete) return
        if (step == AppOnboardingStep.Complete) {
            settings.putBoolean(ONBOARDED_KEY, true)
            Telemetry.onboarding(TelemetryOnboardingStep.AppOnboarded)
        }
        mutableStep.value = step
    }

    fun back() {
        if (mutableStep.value == AppOnboardingStep.AutoPay) moveTo(AppOnboardingStep.Welcome)
    }
}

internal fun appOnboarded(settings: Settings): Boolean = settings.getBoolean(ONBOARDED_KEY, false)

private const val ONBOARDED_KEY = "rayl.onboarded"

/** Auto Pay state for the app onboarding, outside any wallet session. */
internal class AppOnboardingSettings(settings: Settings) {
    private val prices = RaylBitcoinPriceProvider()
    val viewModel = OnboardingViewModel(
        paymentPreferences = DefaultPaymentPreferencesRepository(settings),
        currencyPreferences = DefaultCurrencyPreferences(settings),
        bitcoinPriceProvider = prices
    )

    fun clear() {
        viewModel.clear()
        prices.close()
    }
}
