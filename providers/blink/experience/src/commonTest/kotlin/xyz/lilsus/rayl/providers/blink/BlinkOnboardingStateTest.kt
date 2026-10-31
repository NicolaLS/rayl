package xyz.lilsus.rayl.providers.blink

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import xyz.lilsus.rayl.providers.blink.feature.onboarding.BlinkOnboardingStep
import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode
import xyz.lilsus.raylsuite.feature.currencysettings.DefaultCurrencyPreferences
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingViewModel
import xyz.lilsus.raylsuite.feature.paymentsettings.DefaultPaymentPreferencesRepository

class BlinkOnboardingStateTest {
    @Test
    fun connectingBeforeInterruptionDoesNotCompleteOnboarding() {
        val settings = MapSettings()
        BlinkOnboardingState(settings).moveTo(BlinkOnboardingStep.Connect)

        val restarted = BlinkOnboardingState(settings)

        assertEquals(BlinkOnboardingStep.Connect, restarted.step.value.destination(walletConnected = false))
        assertEquals(BlinkOnboardingStep.Agreement, restarted.step.value.destination(walletConnected = true))
        assertFalse(restarted.canEnterApp(walletConnected = true))
    }

    @Test
    fun unfinishedStageResumesAfterRestartAndReconnection() {
        val settings = MapSettings()
        BlinkOnboardingState(settings).moveTo(BlinkOnboardingStep.Agreement)

        val restarted = BlinkOnboardingState(settings)

        assertEquals(BlinkOnboardingStep.Agreement, restarted.step.value.destination(walletConnected = true))
        assertEquals(BlinkOnboardingStep.Connect, restarted.step.value.destination(walletConnected = false))
        assertFalse(restarted.canEnterApp(walletConnected = false))
        assertEquals(BlinkOnboardingStep.Agreement, restarted.step.value.destination(walletConnected = true))
    }

    @Test
    fun completionSurvivesWalletRemovalAndAllowsOnlyConnectedAppEntry() {
        val settings = MapSettings()
        BlinkOnboardingState(settings).moveTo(BlinkOnboardingStep.Complete)

        val restarted = BlinkOnboardingState(settings)

        assertEquals(BlinkOnboardingStep.Connect, restarted.step.value.destination(walletConnected = false))
        assertFalse(restarted.canEnterApp(walletConnected = false))
        assertTrue(restarted.canEnterApp(walletConnected = true))
        assertEquals(BlinkOnboardingStep.Complete, restarted.step.value.destination(walletConnected = true))
    }

    @Test
    fun freshProviderSetupStartsAtConnection() {
        val state = BlinkOnboardingState(MapSettings())

        assertEquals(BlinkOnboardingStep.Connect, state.step.value)
        assertEquals(BlinkOnboardingStep.Connect, state.step.value.destination(walletConnected = false))
        assertEquals(BlinkOnboardingStep.Agreement, state.step.value.destination(walletConnected = true))
        assertFalse(state.canEnterApp(walletConnected = true))
    }

    @Test
    fun paymentChoicesSurviveAnInterruptedConfigurationStep() {
        val settings = MapSettings()
        fun model() = OnboardingViewModel(
            paymentPreferences = DefaultPaymentPreferencesRepository(settings),
            currencyPreferences = DefaultCurrencyPreferences(settings),
            dispatcher = Dispatchers.Unconfined
        )
        val original = model()
        assertEquals(PaymentConfirmationMode.Above, original.uiState.value.confirmationMode)
        assertEquals(10_000L, original.uiState.value.thresholdSats)
        original.setConfirmationMode(PaymentConfirmationMode.Always)
        original.setThreshold(2_000L)
        original.clear()

        val restarted = model()
        assertEquals(PaymentConfirmationMode.Always, restarted.uiState.value.confirmationMode)
        assertEquals(2_000L, restarted.uiState.value.thresholdSats)
        restarted.clear()
    }
}
