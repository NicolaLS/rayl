package xyz.lilsus.raylsuite.core.telemetry

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.raylapi.TelemetryCatalog
import xyz.lilsus.raylsuite.core.raylapi.TelemetryPaymentOutcome
import xyz.lilsus.raylsuite.core.raylapi.TelemetryWallet
import xyz.lilsus.raylsuite.core.raylapi.telemetryEvent

class TelemetrySenderTest {
    private val event = telemetryEvent(
        TelemetryCatalog.payment,
        dimensions = mapOf("wallet" to TelemetryWallet.Rayl, "outcome" to TelemetryPaymentOutcome.Paid),
        measures = mapOf("submit_to_outcome_ms" to 1_840L, "qr_to_admitted_ms" to null)
    )

    private fun sender(engine: MockEngine) = TelemetrySender(
        device = TelemetryDevice(platform = "android", osMajor = 36, model = "Pixel 8"),
        appVersion = "1.2.0",
        baseUrl = "https://rayl.test",
        client = { HttpClient(engine) }
    )

    @Test
    fun postsTheBatchInTheShapeTheBackendStores() = runTest {
        var body = ""
        val sender = sender(
            MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("https://rayl.test/v1/telemetry", request.url.toString())
                body = request.body.toByteArray().decodeToString()
                respond("", HttpStatusCode.NoContent)
            }
        )

        assertTrue(sender.send(listOf(event)))

        // The backend of a released app parses exactly this; a measure that was not taken is absent.
        assertEquals(
            Json.parseToJsonElement(
                """
                {"platform":"android","appVersion":"1.2.0","osMajor":36,"deviceModel":"Pixel 8",
                 "events":[{"name":"payment","dimensions":{"wallet":"rayl","outcome":"paid"},
                            "measures":{"submit_to_outcome_ms":1840}}]}
                """
            ),
            Json.parseToJsonElement(body)
        )
    }

    @Test
    fun asksToRetryOnlyWhenTheBackendCouldTakeTheBatchLater() = runTest {
        assertFalse(sender(MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }).send(listOf(event)))
        assertFalse(sender(MockEngine { throw IOException("offline") }).send(listOf(event)))
        // A batch the backend refuses would be refused again.
        assertTrue(sender(MockEngine { respond("", HttpStatusCode.BadRequest) }).send(listOf(event)))
        assertTrue(sender(MockEngine { respond("", HttpStatusCode.PayloadTooLarge) }).send(listOf(event)))
    }

    @Test
    fun reducesADeviceModelToWhatTheBackendAccepts() {
        assertEquals("SM-G970F", "SM-G970F".asTelemetryDeviceModel())
        assertEquals("moto g(7) power", "moto g(7) power".asTelemetryDeviceModel())
        assertEquals("Redmi_Note-", "Redmi_Noteé".asTelemetryDeviceModel())
        assertEquals("unknown", "".asTelemetryDeviceModel())
        assertEquals(64, "x".repeat(80).asTelemetryDeviceModel().length)
    }
}
