package xyz.lilsus.raylsuite.backend.rayl

import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import xyz.lilsus.raylsuite.core.raylapi.BitcoinPriceHistory

/** An upstream that knows bitcoin's recent closing prices in a fiat currency. */
fun interface PriceHistorySource {
    /** Closing prices since [sinceSeconds], oldest first. Throws when the upstream fails. */
    suspend fun fetch(currency: String, sinceSeconds: Long): List<Double>
}

/**
 * Serves the last 24 hours of bitcoin's price for the app's chart. Each currency is fetched when
 * it is first asked for and reused for [maxAgeMs]; a failing upstream keeps serving the previous
 * chart until it is [staleAfterMs] old. Unlike [PriceBoard] this has one source, because a chart
 * that is a little off harms nobody and no payment is quoted from it.
 */
class PriceHistoryBoard(
    private val source: PriceHistorySource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = 5 * 60_000L,
    private val staleAfterMs: Long = 60 * 60_000L,
    private val log: (String) -> Unit = ::println
) {
    private val charts = ConcurrentHashMap<String, Chart>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Null for a currency that is not charted, and when no usable chart is known. */
    suspend fun history(currency: String): BitcoinPriceHistory? {
        if (currency !in CURRENCIES) return null
        val chart = locks.getOrPut(currency) { Mutex() }.withLock {
            val known = charts[currency]
            if (known != null && clock() - known.fetchedAtMs <= maxAgeMs) {
                known
            } else {
                fetch(currency)?.also { charts[currency] = it } ?: known
            }
        }
        return chart
            ?.takeIf { clock() - it.fetchedAtMs <= staleAfterMs }
            ?.let { BitcoinPriceHistory(currency, INTERVAL_SECONDS, it.prices) }
    }

    private suspend fun fetch(currency: String): Chart? = try {
        val now = clock()
        val prices = source.fetch(currency, sinceSeconds = now / 1_000 - WINDOW_SECONDS)
            .filter { it.isFinite() && it > 0 }
        check(prices.size >= 2) { "no usable prices" }
        Chart(prices, now)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        // Any failure, an Error included, costs one chart and never the service.
        log("price history for $currency failed: ${failure.logLine()}")
        null
    }

    private class Chart(val prices: List<Double>, val fetchedAtMs: Long)

    companion object {
        /** The currencies the app can display, which are also the ones Kraken quotes. */
        val CURRENCIES = setOf("USD", "EUR", "GBP", "CAD", "CHF", "AUD", "JPY")
        const val INTERVAL_SECONDS = 15 * 60L
        const val WINDOW_SECONDS = 24 * 60 * 60L
    }
}

class KrakenHistorySource(private val http: HttpClient) : PriceHistorySource {
    override suspend fun fetch(currency: String, sinceSeconds: Long): List<Double> = parse(
        http.getJson("https://api.kraken.com/0/public/OHLC") {
            parameter("pair", "XBT$currency")
            parameter("interval", PriceHistoryBoard.INTERVAL_SECONDS / 60)
            parameter("since", sinceSeconds)
        }
    )

    companion object {
        /**
         * The result holds one array of candles under a pair name that varies (`XXBTZUSD`,
         * `XBTCHF`), next to a `last` cursor. A candle is `[time, open, high, low, close, ...]`.
         */
        fun parse(body: JsonElement): List<Double> {
            val root = body.jsonObject
            val errors = root["error"] as? JsonArray
            check(errors.isNullOrEmpty()) { "kraken error $errors" }
            val candles = root.getValue("result").jsonObject.values
                .filterIsInstance<JsonArray>()
                .firstOrNull()
                ?: error("kraken sent no candles")
            return candles.mapNotNull { candle ->
                ((candle as? JsonArray)?.getOrNull(4) as? JsonPrimitive)?.content?.toDoubleOrNull()
            }
        }
    }
}
