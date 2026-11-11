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
