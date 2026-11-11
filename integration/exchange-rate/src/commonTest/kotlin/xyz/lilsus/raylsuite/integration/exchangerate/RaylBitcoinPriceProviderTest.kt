package xyz.lilsus.raylsuite.integration.exchangerate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class RaylBitcoinPriceProviderTest {
    private val prices = """{"asOf":1790705710,"prices":{"EUR":55000.0,"USD":60000.0},"sources":3}"""

    private fun backend(onRequest: suspend () -> Unit = {}, status: HttpStatusCode = HttpStatusCode.OK) = HttpClient(
        MockEngine { request ->
            assertEquals("https://rayl.test/v1/bitcoin/prices", request.url.toString())
            onRequest()
            respond(
                content = if (status == HttpStatusCode.OK) prices else """{"error":"prices_unavailable"}""",
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
    )

    @Test
    fun oneRequestServesEveryCurrencyByNormalizedCode() = runTest {
        var requestCount = 0
        val client = backend(onRequest = {
            requestCount += 1
            delay(10)
        })
        val provider = RaylBitcoinPriceProvider(client = client, baseUrl = "https://rayl.test", clock = { 100L })

        assertEquals(60_000.0, provider.pricePerBitcoin("usd"))
        assertEquals(60_000.0, provider.pricePerBitcoin("USD"))
        assertEquals(55_000.0, provider.pricePerBitcoin("EUR"))
        assertNull(provider.pricePerBitcoin("CHF"))
        assertEquals(1, requestCount)

        client.close()
    }

    @Test
    fun pricesAreReusedForAMinuteThenFetchedAgain() = runTest {
        var requestCount = 0
        var now = 1_790_705_710_000L
        val start = now
        val client = backend(onRequest = { requestCount += 1 })
        val provider = RaylBitcoinPriceProvider(client = client, baseUrl = "https://rayl.test", clock = { now })

        provider.pricePerBitcoin("USD")
        now = start + 59_999L
        provider.pricePerBitcoin("USD")
        assertEquals(1, requestCount)

        now = start + 60_000L
        provider.pricePerBitcoin("USD")
        assertEquals(2, requestCount)
        client.close()
    }

    @Test
    fun unavailableBackendYieldsNoPrice() = runTest {
        val client = backend(status = HttpStatusCode.ServiceUnavailable)
        val provider = RaylBitcoinPriceProvider(client = client, baseUrl = "https://rayl.test", clock = { 100L })

        assertNull(provider.pricePerBitcoin("USD"))
        client.close()
    }

    @Test
    fun aFailedFetchIsRetriedOnTheNextRequest() = runTest {
        // A failure must not be cached for a minute: the next payment tries again (PR-18).
        var status = HttpStatusCode.ServiceUnavailable
        val client = HttpClient(
            MockEngine {
                respond(
                    content = if (status == HttpStatusCode.OK) prices else "",
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        )
        val provider = RaylBitcoinPriceProvider(client = client, baseUrl = "https://rayl.test", clock = { 100L })

        assertNull(provider.pricePerBitcoin("USD"))
        status = HttpStatusCode.OK
        assertEquals(60_000.0, provider.pricePerBitcoin("USD"))
        client.close()
    }

    @Test
    fun aBrokenResponseOrNetworkErrorYieldsNoPrice() = runTest {
        val bodies = listOf("<html>Bad gateway</html>", "{}", """{"asOf":1}""", """{"asOf":1,"prices":"60000"}""")
        bodies.forEach { body ->
            val client = HttpClient(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) })
            val provider = RaylBitcoinPriceProvider(client = client, baseUrl = "https://rayl.test", clock = { 100L })

            assertNull(provider.pricePerBitcoin("USD"), body)
            client.close()
        }
        val offline = HttpClient(MockEngine { throw IllegalStateException("connection reset") })
        assertNull(RaylBitcoinPriceProvider(client = offline, baseUrl = "https://rayl.test").pricePerBitcoin("USD"))
        offline.close()
    }

    @Test
    fun anUnusablePriceForOneCurrencyLeavesTheOthersUsable() = runTest {
        val body = """{"asOf":1,"prices":{"USD":0.0,"EUR":-55000.0,"GBP":50000.0}}"""
        val client = HttpClient(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        val provider = RaylBitcoinPriceProvider(client = client, baseUrl = "https://rayl.test", clock = { 100L })

        assertNull(provider.pricePerBitcoin("USD"))
        assertNull(provider.pricePerBitcoin("EUR"))
        assertEquals(50_000.0, provider.pricePerBitcoin("GBP"))
        client.close()
    }

    @Test
    fun cancellationIsPropagatedInsteadOfBecomingAMissingPrice() = runTest {
        // A cancelled payment session must not continue as "no rate".
        val client = HttpClient(MockEngine { throw CancellationException("session reset") })
        val provider = RaylBitcoinPriceProvider(client = client, baseUrl = "https://rayl.test")

        assertFailsWith<CancellationException> { provider.pricePerBitcoin("USD") }
        client.close()
    }

    @Test
    fun aDayOfPricesIsFetchedPerCurrencyAndReusedForFiveMinutes() = runTest {
        val requested = mutableListOf<String>()
        var now = 1_000L
        val client = HttpClient(
            MockEngine { request ->
                requested += request.url.toString()
                respond(
                    """{"currency":"EUR","intervalSeconds":900,"prices":[55000.0,0.0,55500.0]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        )
        val provider = RaylBitcoinPriceProvider(client = client, baseUrl = "https://rayl.test", clock = { now })

        assertEquals(listOf(55_000.0, 55_500.0), provider.priceHistory(" eur "))
        now += 299_999L
        provider.priceHistory("EUR")
        assertEquals(listOf("https://rayl.test/v1/bitcoin/prices/history?currency=EUR"), requested)

        now += 1L
        provider.priceHistory("EUR")
        assertEquals(2, requested.size)
        client.close()
    }

    @Test
    fun aMissingOrBrokenHistoryIsNoHistory() = runTest {
        // A backend without the endpoint answers 404; the widget then shows the price alone.
        val missing = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
        assertNull(RaylBitcoinPriceProvider(client = missing, baseUrl = "https://rayl.test").priceHistory("USD"))
        missing.close()

        val short = HttpClient(MockEngine { respond("""{"currency":"USD","intervalSeconds":900,"prices":[1.0]}""") })
        assertNull(RaylBitcoinPriceProvider(client = short, baseUrl = "https://rayl.test").priceHistory("USD"))
        short.close()
    }
}
