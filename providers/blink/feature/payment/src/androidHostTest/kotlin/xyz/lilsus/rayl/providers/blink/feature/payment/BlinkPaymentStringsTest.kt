package xyz.lilsus.rayl.providers.blink.feature.payment

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.lilsus.raylsuite.core.testing.assertStringResourcesFollowKeys

/** Blink payment text (its errors and funding wallets): what Android shows must be the same text as on iOS, in every language. */
@RunWith(RobolectricTestRunner::class)
class BlinkPaymentStringsTest {
    @Test
    fun everyKeyShowsItsOwnTextInEveryLanguage() =
        assertStringResourcesFollowKeys(BlinkPaymentTextKey.entries, ::blinkPaymentStringResource)
}
