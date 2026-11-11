package xyz.lilsus.raylsuite.backend.rayl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.raylapi.BitcoinPrices

class PriceBoardTest {
    private var now = 1_000_000L

    private class FakeSource(override val name: String, var quotes: Map<String, Double>?) :
        PriceSource {
        override suspend fun fetch() = quotes ?: error("down")
    }

    private fun board(vararg sources: PriceSource) =
        PriceBoard(sources.toList(), clock = { now }, maxAgeMs = 60_000, log = {})

    @Test
    fun servesTheMedianPerCurrency() = runTest {
        val board = board(
            FakeSource("a", mapOf("USD" to 100.0, "EUR" to 90.0)),
            FakeSource("b", mapOf("USD" to 101.0, "EUR" to 92.0)),
            FakeSource("c", mapOf("USD" to 500.0, "ARS" to 7.0))
        )
        board.refresh()
        assertEquals(
            BitcoinPrices(
                asOf = 1_000,
                prices = mapOf("ARS" to 7.0, "EUR" to 91.0, "USD" to 101.0)
            ),
            board.current()
        )
    }

    @Test
    fun dropsInvalidQuotes() = runTest {
        val board = board(
            FakeSource("a", mapOf("USD" to 100.0, "usd" to 1.0, "EUR" to -1.0, "GBP" to Double.NaN))
        )
        board.refresh()
        assertEquals(mapOf("USD" to 100.0), board.current()?.prices)
    }

    @Test
    fun aFailingSourceKeepsItsQuotesUntilTheyAreStale() = runTest {
        val flaky = FakeSource("flaky", mapOf("USD" to 100.0))
        val steady = FakeSource("steady", mapOf("USD" to 110.0))
        val board = board(flaky, steady)
        board.refresh()

        flaky.quotes = null
        now += 30_000
        board.refresh()
        assertEquals(BitcoinPrices(asOf = 1_000, prices = mapOf("USD" to 105.0)), board.current())

        now += 31_000
        assertEquals(BitcoinPrices(asOf = 1_030, prices = mapOf("USD" to 110.0)), board.current())
    }

    @Test
    fun aSourceThatThrowsAnErrorCostsOnlyItsOwnQuotes() = runTest {
        val overflowing = object : PriceSource {
            override val name = "overflowing"
            override suspend fun fetch(): Map<String, Double> = throw StackOverflowError()
        }
        val board = board(overflowing, FakeSource("steady", mapOf("USD" to 110.0)))

        board.refresh()
        board.refresh()

        assertEquals(mapOf("USD" to 110.0), board.current()?.prices)
    }

    @Test
    fun nothingFreshMeansNoPrices() = runTest {
        val source = FakeSource("a", mapOf("USD" to 100.0))
        val board = board(source)
        assertNull(board.current())

        board.refresh()
        source.quotes = null
        now += 61_000
        board.refresh()
        assertNull(board.current())
    }
}
