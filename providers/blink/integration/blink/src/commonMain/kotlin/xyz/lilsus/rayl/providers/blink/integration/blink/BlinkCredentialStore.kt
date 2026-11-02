package xyz.lilsus.rayl.providers.blink.integration.blink

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.settings.SecureStringStore

internal data class BlinkCredentials(
    val apiKey: String,
    val selectedFundingWallet: BlinkFundingWallet?
) {
    override fun toString(): String =
        "BlinkCredentials(apiKey=<redacted>, selectedFundingWallet=$selectedFundingWallet)"
}

internal class BlinkCredentialStore(
    private val settings: SecureStringStore,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    fun read(): BlinkCredentials? {
        val encoded = settings.getStringOrNull(CREDENTIALS_KEY) ?: return null
        return runCatching {
            json.decodeFromString<StoredBlinkCredentials>(encoded).toCredentials()
        }.getOrNull()
    }

    fun save(credentials: BlinkCredentials) {
        require(credentials.apiKey.isNotBlank()) { "Blink API key cannot be blank" }
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
private data class StoredBlinkCredentials(
    val apiKey: String,
    val selectedFundingWallet: StoredBlinkFundingWallet?
)

@Serializable
private data class StoredBlinkFundingWallet(val id: String, val currency: String)

private fun BlinkCredentials.toStored(): StoredBlinkCredentials = StoredBlinkCredentials(
    apiKey = apiKey,
    selectedFundingWallet =
        selectedFundingWallet?.let { wallet ->
            StoredBlinkFundingWallet(
                id = wallet.id,
                currency = wallet.currency.name
            )
        }
)

private fun StoredBlinkCredentials.toCredentials(): BlinkCredentials? {
    if (apiKey.isBlank()) return null
    val fundingWallet = selectedFundingWallet?.toFundingWallet()
    if (selectedFundingWallet != null && fundingWallet == null) return null
    return BlinkCredentials(
        apiKey = apiKey,
        selectedFundingWallet = fundingWallet
    )
}

private fun StoredBlinkFundingWallet.toFundingWallet(): BlinkFundingWallet? {
    val walletId = id.trim().takeIf(String::isNotEmpty) ?: return null
    val walletCurrency = BlinkWalletCurrency.entries.firstOrNull { it.name == currency }
        ?: return null
    return BlinkFundingWallet(walletId, walletCurrency)
}

private const val CREDENTIALS_KEY = "credentials"
