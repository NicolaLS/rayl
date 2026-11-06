package xyz.lilsus.rayl.providers.nwc.feature.payment

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.lilsus.raylsuite.core.testing.assertStringResourcesFollowKeys

/** NWC payment text: what Android shows must be the same text as on iOS, in every language. */
@RunWith(RobolectricTestRunner::class)
class NwcPaymentStringsTest {
    @Test
    fun everyKeyShowsItsOwnTextInEveryLanguage() =
        assertStringResourcesFollowKeys(NwcPaymentTextKey.entries, ::nwcPaymentStringResource)
}
