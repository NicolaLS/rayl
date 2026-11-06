package xyz.lilsus.raylsuite.feature.paymentui

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.lilsus.raylsuite.core.testing.assertStringResourcesFollowKeys

/** shared payment text (errors, toasts, results): what Android shows must be the same text as on iOS, in every language. */
@RunWith(RobolectricTestRunner::class)
class PaymentMessagesTest {
    @Test
    fun everyKeyShowsItsOwnTextInEveryLanguage() =
        assertStringResourcesFollowKeys(PaymentUiTextKey.entries, {
            (it as PaymentUiTextKey).androidStringResource()
        })
}
