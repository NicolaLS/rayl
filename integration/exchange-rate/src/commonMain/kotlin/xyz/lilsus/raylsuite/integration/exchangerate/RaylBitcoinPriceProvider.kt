package xyz.lilsus.raylsuite.integration.exchangerate

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.network.createHttpClient
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceHistoryProvider
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider
import xyz.lilsus.raylsuite.core.raylapi.BitcoinPriceHistory
import xyz.lilsus.raylsuite.core.raylapi.BitcoinPrices
import xyz.lilsus.raylsuite.core.raylapi.RaylApi

/** Reads bitcoin prices from the Rayl backend, which aggregates several public sources. */
class RaylBitcoinPriceProvider(
    private val client: HttpClient = createHttpClient(),
    private val baseUrl: String = RaylApi.BASE_URL,
    private val clock: () -> Long = ::platformCurrentTimeMillis
) : BitcoinPriceProvider,
    BitcoinPriceHistoryProvider {
    fun close() = client.close()

    private val cacheMutex = Mutex()
    private var cached: CachedPrices? = null
    private val historyMutex = Mutex()
    private val cachedHistory = mutableMapOf<String, CachedHistory>()

    override suspend fun pricePerBitcoin(fiatCurrencyCode: String): Double? {
        val currencyCode =
            fiatCurrencyCode
                .trim()
                .uppercase()
                .takeIf(String::isNotEmpty)
                ?: return null
        val prices = cacheMutex.withLock {
            cached
                ?.takeIf { clock() - it.storedAtMs < CACHE_TTL_MS }
                ?.prices
                ?: fetchPrices()?.also { cached = CachedPrices(it, storedAtMs = clock()) }
        }
        return prices?.get(currencyCode)?.takeIf { it.isFinite() && it > 0.0 }
    }

    override suspend fun priceHistory(fiatCurrencyCode: String): List<Double>? {
        val currencyCode =
            fiatCurrencyCode
                .trim()
                .uppercase()
                .takeIf(String::isNotEmpty)
                ?: return null
        return historyMutex.withLock {
            cachedHistory[currencyCode]
                ?.takeIf { clock() - it.storedAtMs < HISTORY_CACHE_TTL_MS }
                ?.prices
                ?: fetchHistory(currencyCode)?.also {
                    cachedHistory[currencyCode] = CachedHistory(it, storedAtMs = clock())
                }
        }
    }

    private suspend fun fetchHistory(currencyCode: String): List<Double>? = try {
        val response = client.get(baseUrl + RaylApi.BITCOIN_PRICE_HISTORY_PATH) {
            parameter(RaylApi.BITCOIN_PRICE_HISTORY_CURRENCY, currencyCode)
        }
        if (response.status.isSuccess()) {
            json.decodeFromString(BitcoinPriceHistory.serializer(), response.bodyAsText())
                .prices
                .filter { it.isFinite() && it > 0.0 }
                .takeIf { it.size >= 2 }
        } else {
            null
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }

    private suspend fun fetchPrices(): Map<String, Double>? = try {
        val response = client.get(baseUrl + RaylApi.BITCOIN_PRICES_PATH)
        if (response.status.isSuccess()) {
            json.decodeFromString(BitcoinPrices.serializer(), response.bodyAsText()).prices
        } else {
            null
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }

    private class CachedPrices(val prices: Map<String, Double>, val storedAtMs: Long)

    private class CachedHistory(val prices: List<Double>, val storedAtMs: Long)

    private companion object {
        const val CACHE_TTL_MS = 60_000L

        /** A day's chart barely changes in five minutes, and the backend caches it as long. */
        const val HISTORY_CACHE_TTL_MS = 5 * 60_000L
        val json = Json { ignoreUnknownKeys = true }
    }
}

internal expect fun platformCurrentTimeMillis(): Long
