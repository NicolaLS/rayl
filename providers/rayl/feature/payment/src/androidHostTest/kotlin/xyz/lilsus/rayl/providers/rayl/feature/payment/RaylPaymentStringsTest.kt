package xyz.lilsus.rayl.providers.rayl.feature.payment

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.lilsus.raylsuite.core.testing.assertStringResourcesFollowKeys

/** Rayl's wallet payment text: what Android shows must be the same text as on iOS, in every language. */
@RunWith(RobolectricTestRunner::class)
class RaylPaymentStringsTest {
    @Test
    fun everyKeyShowsItsOwnTextInEveryLanguage() =
        assertStringResourcesFollowKeys(RaylPaymentTextKey.entries, ::raylPaymentStringResource)
}
