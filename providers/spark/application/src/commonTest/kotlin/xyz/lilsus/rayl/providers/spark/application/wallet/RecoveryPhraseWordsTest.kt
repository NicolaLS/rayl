package xyz.lilsus.rayl.providers.spark.application.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryPhraseWordsTest {
    @Test
    fun completesOnlyUnambiguousPrefixes() {
        assertEquals("abandon", RecoveryPhraseWords.completion("aban"))
        assertNull(RecoveryPhraseWords.completion("ab"))
        // A complete word stays as typed, even when longer words start with it.
        assertNull(RecoveryPhraseWords.completion("act"))
    }
}
