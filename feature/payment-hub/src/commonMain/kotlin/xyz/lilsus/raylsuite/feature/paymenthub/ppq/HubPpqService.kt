package xyz.lilsus.raylsuite.feature.paymenthub.ppq

import xyz.lilsus.raylsuite.core.settings.SecureStringStore

/**
 * The ppq.ai account behind every PPQ.AI widget. The API key is entered once and kept in the
 * platform's credential store, never in the Hub document.
 */
class HubPpqService(private val secrets: SecureStringStore, val api: PpqApi) {
    fun apiKey(): String? = secrets.getStringOrNull(API_KEY)?.takeIf(String::isNotBlank)

    fun saveApiKey(value: String) = secrets.putString(API_KEY, value)

    fun forgetApiKey() = secrets.remove(API_KEY)

    fun close() = api.close()

    companion object {
        /** The credential store the platforms open for the Hub. */
        const val STORAGE_NAME = "payment_hub_services"
        private const val API_KEY = "ppq.apiKey"
    }
}

fun createHubPpqService(secrets: SecureStringStore) = HubPpqService(secrets, KtorPpqApi())
