package xyz.lilsus.rayl.providers.spark

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.lilsus.raylsuite.core.model.WalletStartMode

class SparkOnboardingStateTest {
    @Test
    fun connectingBeforeAgreementDoesNotAdmitPaymentLinks() {
        val state = SparkOnboardingState(MapSettings(), WalletStartMode.Fresh)
        assertFalse(state.canHandlePaymentDeepLink(walletConnected = true))
        state.complete()
        assertTrue(state.canHandlePaymentDeepLink(walletConnected = true))
        assertFalse(state.canHandlePaymentDeepLink(walletConnected = false))
    }

    @Test
    fun interruptedEducationIsNotMistakenForAnExistingEducatedWallet() {
        val settings = MapSettings()
        assertFalse(SparkOnboardingState(settings, WalletStartMode.Fresh).completed.value)
        assertFalse(SparkOnboardingState(settings, WalletStartMode.Restore).completed.value)
    }

    @Test
    fun reconnectingAfterRemovalKeepsCompletedEducation() {
        val settings = MapSettings()
        SparkOnboardingState(settings, WalletStartMode.Fresh).complete()
        assertTrue(SparkOnboardingState(settings, WalletStartMode.Fresh).completed.value)
    }

    @Test
    fun existingWalletsFromBeforeProgressPersistenceDoNotRepeatEducation() {
        val settings = MapSettings()
        assertTrue(SparkOnboardingState(settings, WalletStartMode.Restore).completed.value)
        assertTrue(SparkOnboardingState(settings, WalletStartMode.Fresh).completed.value)
    }
}
