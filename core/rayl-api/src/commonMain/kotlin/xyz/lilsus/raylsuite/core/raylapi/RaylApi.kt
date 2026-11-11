package xyz.lilsus.raylsuite.core.raylapi

import kotlinx.serialization.Serializable

/** Wire contract between Rayl clients and the Rayl backend (`backend/rayl`). */
object RaylApi {
    const val BASE_URL = "https://rayl-production.up.railway.app"
    const val BITCOIN_PRICES_PATH = "/v1/bitcoin/prices"

    /** Takes [BITCOIN_PRICE_HISTORY_CURRENCY] and answers with a [BitcoinPriceHistory]. */
    const val BITCOIN_PRICE_HISTORY_PATH = "/v1/bitcoin/prices/history"
    const val BITCOIN_PRICE_HISTORY_CURRENCY = "currency"

    /** Accepts a [TelemetryBatch]; see `TelemetryCatalog.kt` for everything it can contain. */
    const val TELEMETRY_PATH = "/v1/telemetry"

    /** Accepts a [FeedbackSubmission]. */
    const val FEEDBACK_PATH = "/v1/feedback"
}

/**
 * Bitcoin's price in each fiat currency the backend has a fresh quote for, keyed by upper-case
 * ISO 4217 code. [asOf] is the Unix time in seconds of the oldest source quote that contributed.
 */
@Serializable
data class BitcoinPrices(val asOf: Long, val prices: Map<String, Double>)

/**
 * Bitcoin's price in [currency] over the last 24 hours, oldest first, one figure every
 * [intervalSeconds]. It draws a chart; payments are quoted from [BitcoinPrices].
 */
@Serializable
data class BitcoinPriceHistory(
    val currency: String,
    val intervalSeconds: Long,
    val prices: List<Double>
)

@Serializable
data class RaylApiError(val error: String)

/**
 * What a tester wrote in the app's feedback form, sent to [RaylApi.FEEDBACK_PATH]. Unlike usage
 * data this is free text the user chose to send, so it is kept for reading, not counted. It
 * carries no device or account information; [contact] is only what the user typed to be
 * answered, if they wanted to.
 */
@Serializable
data class FeedbackSubmission(
    val kind: String,
    val platform: String,
    val message: String,
    val contact: String? = null
)

object FeedbackCatalog {
    const val MAX_MESSAGE_LENGTH = 2_000
    const val MAX_CONTACT_LENGTH = 200
    const val KIND_WIDGET = "widget"
    const val KIND_IDEA = "idea"
    const val KIND_PROBLEM = "problem"
    const val KIND_OTHER = "other"
    val kinds = setOf(KIND_WIDGET, KIND_IDEA, KIND_PROBLEM, KIND_OTHER)
    val platforms = setOf("android", "ios")

    /** The submission trimmed to what the backend stores, or null when it is not acceptable. */
    fun sanitize(submission: FeedbackSubmission): FeedbackSubmission? {
        val message = submission.message.trim()
        val contact = submission.contact?.trim()?.takeIf(String::isNotEmpty)
        if (submission.kind !in kinds || submission.platform !in platforms) return null
        if (message.isEmpty() || message.length > MAX_MESSAGE_LENGTH) return null
        if (contact != null && contact.length > MAX_CONTACT_LENGTH) return null
        return FeedbackSubmission(submission.kind, submission.platform, message, contact)
    }
}
