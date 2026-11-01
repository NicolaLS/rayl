package xyz.lilsus.raylsuite.integration.exchangerate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun refetchesAfterTheCacheExpires() = runTest {
        var requestCount = 0
        var now = 0L
        val client = backend(onRequest = { requestCount += 1 })
        val provider = RaylBitcoinPriceProvider(client = client, baseUrl = "https://rayl.test", clock = { now })

        provider.pricePerBitcoin("USD")
        now = 60_000L
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
}
