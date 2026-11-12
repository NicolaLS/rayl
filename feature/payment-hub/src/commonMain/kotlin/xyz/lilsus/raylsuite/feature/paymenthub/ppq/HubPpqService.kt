package xyz.lilsus.raylsuite.feature.paymenthub.ppq

import xyz.lilsus.raylsuite.core.settings.SecureStringStore

/**
 * The ppq.ai API keys of the PPQ.AI widgets. Every widget has its own key, kept in the
 * platform's credential store under the widget's id and never in the Hub document.
 */
class HubPpqService(private val secrets: SecureStringStore, val api: PpqApi) {
    /**
     * The key of [widgetId], or null when it has none. Throws while the store cannot be read,
     * which on iOS is whenever the device is locked; the next call then tries again.
     */
    fun apiKey(widgetId: String): String? =
        secrets.getStringOrNull(keyName(widgetId))?.takeIf(String::isNotBlank)

    fun saveApiKey(widgetId: String, value: String) = secrets.putString(keyName(widgetId), value)

    fun forgetApiKey(widgetId: String) = secrets.remove(keyName(widgetId))

    fun close() = api.close()

    companion object {
        /** The credential store the platforms open for the Hub. */
        const val STORAGE_NAME = "payment_hub_services"
        private fun keyName(widgetId: String) = "ppq.apiKey.$widgetId"
    }
}

fun createHubPpqService(secrets: SecureStringStore) = HubPpqService(secrets, KtorPpqApi())
