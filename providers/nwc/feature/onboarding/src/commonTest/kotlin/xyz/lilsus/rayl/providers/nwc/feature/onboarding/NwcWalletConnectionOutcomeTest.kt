package xyz.lilsus.rayl.providers.nwc.feature.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals

class NwcWalletConnectionOutcomeTest {
    @Test
    fun earlyConnectionResumesOnboarding() {
        assertEquals(
            NwcWalletConnectionOutcome.ResumeOnboarding,
            nwcWalletConnectionOutcome(fromSettings = false, hasAgreed = false)
        )
    }
}
