package xyz.lilsus.raylsuite.feature.paymenthub.ppq

import kotlin.concurrent.Volatile
import xyz.lilsus.raylsuite.core.settings.SecureStringStore

/**
 * The ppq.ai account behind every PPQ.AI widget. The API key is entered once and kept in the
 * platform's credential store, never in the Hub document.
 */
class HubPpqService(private val secrets: SecureStringStore, val api: PpqApi) {
    @Volatile
    private var known = false

    @Volatile
    private var key: String? = null

    /**
     * The stored key, read from the credential store once. Throws while the store cannot be
     * read, which on iOS is whenever the device is locked; the next call then tries again.
     */
    fun apiKey(): String? {
        if (!known) {
            key = secrets.getStringOrNull(API_KEY)?.takeIf(String::isNotBlank)
            known = true
        }
        return key
    }

    fun saveApiKey(value: String) {
        secrets.putString(API_KEY, value)
        key = value
        known = true
    }

    fun forgetApiKey() {
        secrets.remove(API_KEY)
        key = null
        known = true
    }

    fun close() = api.close()

    companion object {
        /** The credential store the platforms open for the Hub. */
        const val STORAGE_NAME = "payment_hub_services"
        private const val API_KEY = "ppq.apiKey"
    }
}

fun createHubPpqService(secrets: SecureStringStore) = HubPpqService(secrets, KtorPpqApi())
