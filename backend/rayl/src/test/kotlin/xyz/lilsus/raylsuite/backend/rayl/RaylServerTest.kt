package xyz.lilsus.raylsuite.backend.rayl

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.raylapi.BitcoinPrices
import xyz.lilsus.raylsuite.core.raylapi.RaylApi

class RaylServerTest {
    @Test
    fun servesCachedPrices() = testApplication {
        val prices = BitcoinPrices(asOf = 1_790_705_710, prices = mapOf("USD" to 83_428.0))
        application { raylModule { prices } }

        val response = client.get(RaylApi.BITCOIN_PRICES_PATH)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("public, max-age=15", response.headers[HttpHeaders.CacheControl])
        assertEquals(prices, Json.decodeFromString<BitcoinPrices>(response.bodyAsText()))
    }

    @Test
    fun theResponseKeepsTheShapeReleasedAppsParse() = testApplication {
        // Apps in the stores (1.0 onwards) parse exactly this. The data class is shared with the
        // app, so a renamed field would still round-trip here while breaking every released app.
        application {
            raylModule { BitcoinPrices(asOf = 1_790_705_710, prices = mapOf("USD" to 83_428.0)) }
        }

        val body = client.get(RaylApi.BITCOIN_PRICES_PATH).bodyAsText()

        assertEquals(
            Json.parseToJsonElement("""{"asOf":1790705710,"prices":{"USD":83428.0}}"""),
            Json.parseToJsonElement(body)
        )
    }

    @Test
    fun reportsWhenNoSourceIsFresh() = testApplication {
        application { raylModule { null } }

        val response = client.get(RaylApi.BITCOIN_PRICES_PATH)
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }
}
