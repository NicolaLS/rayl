package xyz.lilsus.raylsuite.feature.paymentsettings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode

class PaymentPreferencesRepositoryTest {
    @Test
    fun aChangeStoresOnlyThePreferenceThatChanged() = runTest {
        // Acceptance criterion of MOB-35: a mutation must not rewrite the whole aggregate.
        val settings = MapSettings()
        val repository = DefaultPaymentPreferencesRepository(settings)

        repository.setVibrateOnScan(false)

        assertEquals(setOf("payments.vibrateOnScan"), settings.keys)
    }

    @Test
    fun confirmationSettingsSurviveAnAppRestart() = runTest {
        val settings = MapSettings()
        DefaultPaymentPreferencesRepository(settings).apply {
            setConfirmationMode(PaymentConfirmationMode.Always)
            setConfirmationThreshold(2_000L)
        }

        val restarted = DefaultPaymentPreferencesRepository(settings).current()

        assertEquals(PaymentConfirmationMode.Always, restarted.confirmationMode)
        assertEquals(2_000L, restarted.thresholdSats)
    }
}
