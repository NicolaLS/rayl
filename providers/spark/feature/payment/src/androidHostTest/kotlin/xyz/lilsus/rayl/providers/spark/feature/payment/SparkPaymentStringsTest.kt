package xyz.lilsus.rayl.providers.spark.feature.payment

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.lilsus.raylsuite.core.testing.assertStringResourcesFollowKeys

/** Spark payment text: what Android shows must be the same text as on iOS, in every language. */
@RunWith(RobolectricTestRunner::class)
class SparkPaymentStringsTest {
    @Test
    fun everyKeyShowsItsOwnTextInEveryLanguage() =
        assertStringResourcesFollowKeys(SparkPaymentTextKey.entries, ::sparkPaymentStringResource)
}
