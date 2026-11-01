package xyz.lilsus.rayl.providers.spark.application.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WalletCredentialTest {
    @Test
    fun validPhraseIsNormalized() {
        val credential = WalletCredential.fromMnemonic(
            "  Legal winner thank year wave sausage\n worth useful legal winner thank YELLOW "
        )
        assertEquals(
            "legal winner thank year wave sausage worth useful legal winner thank yellow",
            credential.mnemonic
        )
    }

    @Test
    fun unknownWordsAndBadChecksumsAreRejected() {
        val badChecksum = List(12) { "abandon" }.joinToString(" ")
        val unknownWord = List(11) { "abandon" }.joinToString(" ") + " notaword"
        val wrongLength = List(11) { "abandon" }.joinToString(" ")
        for (phrase in listOf(badChecksum, unknownWord, wrongLength)) {
            assertFailsWith<IllegalArgumentException> { WalletCredential.fromMnemonic(phrase) }
        }
    }

    @Test
    fun passphraseIsKeptExactlyAndSurvivesStorage() {
        val phrase = "legal winner thank year wave sausage worth useful legal winner thank yellow"
        val credential = WalletCredential.fromMnemonic(phrase, " Correct Horse\n ")

        val restored = WalletCredential.decode(credential.encoded())

        assertEquals(phrase, restored.mnemonic)
        assertEquals(" Correct Horse\n ", restored.passphrase)
    }

    @Test
    fun anEmptyPassphraseMeansNone() {
        val phrase = "legal winner thank year wave sausage worth useful legal winner thank yellow"

        val credential = WalletCredential.fromMnemonic(phrase, "")

        assertEquals(null, credential.passphrase)
        assertEquals(phrase, WalletCredential.decode(credential.encoded()).mnemonic)
    }
}
