package xyz.lilsus.rayl.providers.spark.application.wallet

import fr.acinq.bitcoin.MnemonicCode

/**
 * A BIP-39 recovery phrase and its optional passphrase (the "25th word"). A passphrase derives a
 * different wallet from the same phrase, so it is kept exactly as entered: case and spaces count.
 */
class WalletCredential private constructor(val mnemonic: String, val passphrase: String?) {
    /** The stored form. A phrase never contains a line break, so the first one separates them. */
    fun encoded(): String = if (passphrase == null) mnemonic else "$mnemonic\n$passphrase"

    override fun toString(): String = "WalletCredential(<redacted>)"

    companion object {
        /** Throws [IllegalArgumentException] unless [input] is a valid English BIP-39 phrase. */
        fun fromMnemonic(input: String, passphrase: String? = null): WalletCredential {
            val words = input.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
            require(words.size in setOf(12, 15, 18, 21, 24))
            // Unknown words and bad checksums would otherwise reach the SDK and fail as a
            // connection error.
            MnemonicCode.validate(words)
            return WalletCredential(words.joinToString(" "), passphrase?.takeIf(String::isNotEmpty))
        }

        /** Reads [encoded]; throws [IllegalArgumentException] when it is not a valid credential. */
        fun decode(encoded: String): WalletCredential {
            val separator = encoded.indexOf('\n')
            return if (separator < 0) {
                fromMnemonic(encoded)
            } else {
                fromMnemonic(encoded.substring(0, separator), encoded.substring(separator + 1))
            }
        }
    }
}

interface CredentialVault {
    suspend fun load(): CredentialLoadResult
    suspend fun store(credential: WalletCredential): CredentialStoreResult
    suspend fun delete(): CredentialDeleteResult
}

sealed interface CredentialLoadResult {
    data class Available(val credential: WalletCredential) : CredentialLoadResult {
        override fun toString(): String = "CredentialLoadResult.Available(<redacted>)"
    }

    data object Absent : CredentialLoadResult
    data object Unavailable : CredentialLoadResult
    data object Invalidated : CredentialLoadResult
    data object Corrupt : CredentialLoadResult
}

enum class CredentialStoreResult {
    STORED,
    ALREADY_EXISTS,
    UNAVAILABLE,
    INVALIDATED,
    FAILED
}

enum class CredentialDeleteResult {
    ABSENT,
    DELETED,
    FAILED
}
