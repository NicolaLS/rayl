package xyz.lilsus.rayl.providers.spark.application.wallet

import fr.acinq.bitcoin.MnemonicCode

/** The English BIP-39 words, for entering a recovery phrase one word at a time. */
object RecoveryPhraseWords {
    /** Phrase lengths the import offers; any valid BIP-39 length is still accepted when pasted. */
    val OFFERED_COUNTS = listOf(12, 24)
    val VALID_COUNTS = setOf(12, 15, 18, 21, 24)

    private val words: List<String> = MnemonicCode.englishWordlist
    private val wordSet: Set<String> = words.toSet()

    fun isWord(value: String): Boolean = value in wordSet

    /** Whether some word starts with [value], so typing it can still lead to a valid word. */
    fun isPrefix(value: String): Boolean = words.any { it.startsWith(value) }

    /**
     * The word [value] can only complete to, or null. A complete word is left alone: some words
     * are prefixes of others ("act", "actor").
     */
    fun completion(value: String): String? {
        if (value.isEmpty() || isWord(value)) return null
        return words.filter { it.startsWith(value) }.singleOrNull()
    }

    /** Splits typed or pasted text into normalized words. */
    fun split(text: String): List<String> =
        text.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
}
