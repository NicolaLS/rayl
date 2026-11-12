package xyz.lilsus.rayl.providers.rayl.integration.pocket

/**
 * The pocket's BIP-39 phrase, of which this device keeps a copy so launches and payments never
 * prompt. A passkey derives it during setup (see [PocketSetup]) and stays its backup, so that
 * phrase is never shown or exported. Only where no passkey can derive a key does Rayl generate
 * one ([withoutPasskey]); this device then holds the only copy, which the user can read.
 */
class PocketKey internal constructor(
    internal val phrase: String,
    internal val withoutPasskey: Boolean = false
) {
    /** The stored form. A passkey-derived key is the bare phrase, as it always was. */
    internal fun stored(): String = if (withoutPasskey) WITHOUT_PASSKEY_PREFIX + phrase else phrase

    override fun toString(): String = "PocketKey(<redacted>)"

    internal companion object {
        // A phrase is lowercase words and spaces, so it never starts with this.
        private const val WITHOUT_PASSKEY_PREFIX = "without-passkey:"

        fun fromStored(stored: String): PocketKey = if (stored.startsWith(WITHOUT_PASSKEY_PREFIX)) {
            PocketKey(stored.removePrefix(WITHOUT_PASSKEY_PREFIX), withoutPasskey = true)
        } else {
            PocketKey(stored)
        }
    }
}

internal sealed interface PocketKeyLoad {
    data class Present(val key: PocketKey) : PocketKeyLoad

    /** No key was ever stored: the pocket needs setup. */
    data object Absent : PocketKeyLoad

    /** A key may exist but cannot be read now; never replace it without the user's choice. */
    data object Unavailable : PocketKeyLoad
}

internal interface PocketKeyStore {
    suspend fun load(): PocketKeyLoad

    /** Stores [key] only if no key exists; an existing key is never replaced. */
    suspend fun add(key: PocketKey): Boolean

    suspend fun delete(): Boolean
}

internal expect fun secureRandomBytes(size: Int): ByteArray
