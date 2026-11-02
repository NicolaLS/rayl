package xyz.lilsus.rayl.providers.nwc.integration.nwc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.settings.SecureStringStore

internal data class NwcCredentials(
    val connectionUri: String,
    val alias: String?,
    val metadata: NwcWalletMetadata
) {
    /** The connection URI carries the wallet secret. */
    override fun toString(): String =
        "NwcCredentials(connectionUri=<redacted>, alias=$alias, metadata=$metadata)"
}

internal class NwcCredentialStore(
    private val settings: SecureStringStore,
    private val json: Json = Json
) {
    fun read(): NwcCredentials? {
        val encoded = settings.getStringOrNull(CREDENTIALS_KEY) ?: return null
        return runCatching {
            json.decodeFromString<StoredNwcCredentials>(encoded).toCredentials()
        }.getOrNull()
    }

    fun save(credentials: NwcCredentials) {
        require(credentials.connectionUri.isNotBlank()) {
            "NWC connection URI cannot be blank"
        }
        settings.putString(
            CREDENTIALS_KEY,
            json.encodeToString(credentials.toStored())
        )
    }

    fun clear() {
        settings.remove(CREDENTIALS_KEY)
    }
}

@Serializable
private data class StoredNwcCredentials(
    val connectionUri: String,
    val alias: String?,
    val methods: Set<String>,
    val encryptionSchemes: Set<String>,
    val negotiatedEncryption: String?,
    val encryptionDefaultedToNip04: Boolean,
    val notifications: Set<String>,
    val network: String?,
    val color: String?
)

private fun NwcCredentials.toStored(): StoredNwcCredentials = StoredNwcCredentials(
    connectionUri = connectionUri,
    alias = alias,
    methods = metadata.methods,
    encryptionSchemes = metadata.encryptionSchemes,
    negotiatedEncryption = metadata.negotiatedEncryption,
    encryptionDefaultedToNip04 = metadata.encryptionDefaultedToNip04,
    notifications = metadata.notifications,
    network = metadata.network,
    color = metadata.color
)

private fun StoredNwcCredentials.toCredentials(): NwcCredentials? {
    if (connectionUri.isBlank()) return null
    return NwcCredentials(
        connectionUri = connectionUri,
        alias = alias?.trim()?.takeIf(String::isNotEmpty),
        metadata =
            NwcWalletMetadata(
                methods = methods,
                encryptionSchemes = encryptionSchemes,
                negotiatedEncryption = negotiatedEncryption,
                encryptionDefaultedToNip04 = encryptionDefaultedToNip04,
                notifications = notifications,
                network = network,
                color = color
            )
    )
}

private const val CREDENTIALS_KEY = "credentials"
