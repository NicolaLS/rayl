package xyz.lilsus.rayl.providers.nwc

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NwcOnboardingStateTest {
    @Test
    fun freshInstallStartsIncompleteAndRoutesDeepLinksThroughOnboarding() {
        val state = NwcOnboardingState(MapSettings())

        assertFalse(state.completed.value)
        assertEquals(NwcNwcDeepLinkTarget.Onboarding, state.nwcDeepLinkTarget())
        assertFalse(state.canHandlePaymentDeepLink(walletConnected = false))
    }

    @Test
    fun interruptedOnboardingRemainsIncompleteAcrossRestart() {
        val settings = MapSettings()
        NwcOnboardingState(settings)

        val restarted = NwcOnboardingState(settings)

        assertFalse(restarted.completed.value)
        assertEquals(NwcNwcDeepLinkTarget.Onboarding, restarted.nwcDeepLinkTarget())
        assertFalse(restarted.canHandlePaymentDeepLink(walletConnected = true))
    }

    @Test
    fun completionSurvivesRestartAndWalletRemoval() {
        val settings = MapSettings()
        val state = NwcOnboardingState(settings)
        state.complete()

        val restartedWithoutWallet = NwcOnboardingState(settings)

        assertTrue(restartedWithoutWallet.completed.value)
        assertEquals(NwcNwcDeepLinkTarget.Settings, restartedWithoutWallet.nwcDeepLinkTarget())
        assertFalse(restartedWithoutWallet.canHandlePaymentDeepLink(walletConnected = false))
    }
}
