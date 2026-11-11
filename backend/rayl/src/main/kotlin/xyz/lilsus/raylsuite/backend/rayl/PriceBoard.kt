package xyz.lilsus.raylsuite.backend.rayl

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import xyz.lilsus.raylsuite.core.raylapi.BitcoinPrices

/**
 * Keeps each source's latest successful quotes and serves the per-currency median, so one wrong
 * or unreachable upstream cannot move a price on its own. A source that fails keeps its previous
 * quotes until they are older than [maxAgeMs].
 */
class PriceBoard(
    private val sources: List<PriceSource>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = 5 * 60_000L,
    private val log: (String) -> Unit = ::println
) {
    private val snapshots = ConcurrentHashMap<String, Snapshot>()

    suspend fun refresh() = coroutineScope {
        sources.map { source ->
            async {
                try {
                    val quotes = source.fetch().filter { (code, price) ->
                        code.isCurrencyCode() &&
                            price.isFinite() &&
                            price > 0
                    }
                    check(quotes.isNotEmpty()) { "no usable quotes" }
                    snapshots[source.name] = Snapshot(quotes, clock())
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    // An Error from one source must not cancel the others or end the refresh.
                    log("${source.name} failed: ${failure.logLine()}")
                }
            }
        }.awaitAll()
    }

    /** Null when no source has quoted within [maxAgeMs]. */
    fun current(): BitcoinPrices? {
        val now = clock()
        val fresh = snapshots.values.filter { now - it.fetchedAtMs <= maxAgeMs }
        if (fresh.isEmpty()) return null
        val prices = fresh
            .flatMap { it.quotes.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { (_, quotes) -> quotes.median() }
            .toSortedMap()
        return BitcoinPrices(asOf = fresh.minOf { it.fetchedAtMs } / 1_000, prices = prices)
    }

    private class Snapshot(val quotes: Map<String, Double>, val fetchedAtMs: Long)
}

private fun String.isCurrencyCode() = length == 3 && all { it in 'A'..'Z' }

private fun List<Double>.median(): Double {
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}
