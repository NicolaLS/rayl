package xyz.lilsus.rayl.providers.rayl.integration.pocket

/**
 * The pocket's BIP-39 phrase. Rayl never generates it: a passkey derives it during setup (see
 * [PocketSetup]), and this device keeps a copy so launches and payments never prompt. The passkey
 * stays the backup, so the phrase is never shown or exported.
 */
class PocketKey internal constructor(internal val phrase: String) {
    override fun toString(): String = "PocketKey(<redacted>)"
}

internal sealed interface PocketKeyLoad {
    data class Present(val key: PocketKey) : PocketKeyLoad

    /** No key was ever stored: the pocket needs setup. */
    data object Absent : PocketKeyLoad

    /** A key may exist but cannot be read now; never replace it. */
    data object Unavailable : PocketKeyLoad
}

internal interface PocketKeyStore {
    suspend fun load(): PocketKeyLoad

    /** Stores [key] only if no key exists; an existing key is never replaced. */
    suspend fun add(key: PocketKey): Boolean

    suspend fun delete(): Boolean
}

internal expect fun secureRandomBytes(size: Int): ByteArray
