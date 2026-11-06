package xyz.lilsus.raylsuite.feature.paymentsettings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode
import xyz.lilsus.raylsuite.core.model.PaymentPreferences

/**
 * Rayl 1.1 is the first public release: later versions must read the payment settings it stored
 * (test project 2, item 6). These are 1.1's keys and values. If this fails, the format changed:
 * read the old one too, don't change the fixture. Unread, "always ask" would fall back to
 * paying without asking below 10,000 sat.
 */
class StoredPaymentSettingsFrom11Test {
    @Test
    fun readsTheConfirmationChoicesAndSwitches11Stored() = runTest {
        val settings = MapSettings(
            "payments.confirmationMode" to "always",
            "payments.confirmationThresholdSats" to 2_500L,
            "payments.offerToSaveNewTargets" to false,
            "payments.vibrateOnScan" to false,
            "payments.vibrateOnPayment" to false
        )

        assertEquals(
            PaymentPreferences(
                confirmationMode = PaymentConfirmationMode.Always,
                thresholdSats = 2_500,
                offerToSaveNewTargets = false,
                vibrateOnScan = false,
                vibrateOnPayment = false
            ),
            DefaultPaymentPreferencesRepository(settings).current()
        )
    }

    @Test
    fun readsAThreshold11Stored() = runTest {
        val settings = MapSettings(
            "payments.confirmationMode" to "above",
            "payments.confirmationThresholdSats" to 2_500L
        )

        val preferences = DefaultPaymentPreferencesRepository(settings).current()

        assertEquals(PaymentConfirmationMode.Above, preferences.confirmationMode)
        assertEquals(2_500, preferences.thresholdSats)
    }
}
