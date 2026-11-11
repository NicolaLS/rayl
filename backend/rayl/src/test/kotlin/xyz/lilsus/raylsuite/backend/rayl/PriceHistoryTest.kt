package xyz.lilsus.raylsuite.backend.rayl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.raylapi.BitcoinPriceHistory

class PriceHistoryTest {
    private var now = 1_790_000_000_000L
    private var upstream: List<Double>? = listOf(100.0, 101.0, 102.0)
    private val requests = mutableListOf<Pair<String, Long>>()

    private val board = PriceHistoryBoard(
        source = { currency, since ->
            requests += currency to since
            upstream ?: error("down")
        },
        clock = { now },
        log = {}
    )

    @Test
    fun aCurrencyIsFetchedForTheLastDayAndReusedForFiveMinutes() = runTest {
        val expected = BitcoinPriceHistory("EUR", 900, listOf(100.0, 101.0, 102.0))
        assertEquals(expected, board.history("EUR"))
        now += 4 * 60_000
        assertEquals(expected, board.history("EUR"))
        assertEquals(listOf("EUR" to 1_790_000_000L - 86_400), requests)

        now += 2 * 60_000
        upstream = listOf(101.0, 102.0, 103.0)
        assertEquals(listOf(101.0, 102.0, 103.0), board.history("EUR")?.prices)
        assertEquals(2, requests.size)
    }

    @Test
    fun aFailingUpstreamServesThePreviousChartForAnHour() = runTest {
        board.history("USD")
        upstream = null

        now += 30 * 60_000
        assertEquals(listOf(100.0, 101.0, 102.0), board.history("USD")?.prices)

        now += 31 * 60_000
        assertNull(board.history("USD"))
    }

    @Test
    fun onlyTheAppsCurrenciesReachTheUpstream() = runTest {
        assertNull(board.history("ARS"))
        assertNull(board.history("usd; drop"))
        assertEquals(emptyList(), requests)
    }

    @Test
    fun aChartNeedsAtLeastTwoUsablePrices() = runTest {
        upstream = listOf(Double.NaN, 100.0, -1.0)
        assertNull(board.history("USD"))
    }

    @Test
    fun readsTheClosingPriceOfEachKrakenCandle() {
        val body = Json.parseToJsonElement(
            """
            {"error":[],"result":{"XXBTZUSD":[
              [1790000000,"84000.1","84100.0","83900.0","84050.5","84010.0","1.2",12],
              [1790000900,"84050.5","84200.0","84000.0","84150.0","84100.0","0.8",9]
            ],"last":1790000000}}
            """.trimIndent()
        )
        assertEquals(listOf(84_050.5, 84_150.0), KrakenHistorySource.parse(body))
    }

    @Test
    fun aKrakenErrorIsAFailure() {
        val body = Json.parseToJsonElement("""{"error":["EQuery:Unknown asset pair"]}""")
        assertFailsWith<IllegalStateException> { KrakenHistorySource.parse(body) }
    }
}
