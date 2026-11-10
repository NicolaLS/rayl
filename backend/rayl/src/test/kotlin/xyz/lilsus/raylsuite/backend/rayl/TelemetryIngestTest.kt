package xyz.lilsus.raylsuite.backend.rayl

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.lilsus.raylsuite.core.raylapi.RaylApi

class TelemetryIngestTest {
    private val stored = mutableListOf<TelemetryRow>()

    // 2026-11-10T14:37:42Z; the hour starts at 1_794_319_200.
    private val now = 1_794_321_462L

    private fun telemetryTest(
        store: TelemetryStore? = TelemetryStore { rows -> stored += rows },
        block: suspend ApplicationTestBuilder.() -> Unit
    ) = testApplication {
        application { raylModule(store, nowSeconds = { now }) { null } }
        block()
    }

    private suspend fun ApplicationTestBuilder.send(body: String): HttpResponse =
        client.post(RaylApi.TELEMETRY_PATH) { setBody(body) }

    private fun batch(events: String, deviceModel: String = "Pixel 8") =
        """{"platform":"android","appVersion":"1.2.0","osMajor":16,"deviceModel":"$deviceModel","events":[$events]}"""

    @Test
    fun storesTheBatchAReleasedAppSends() = telemetryTest {
        // Apps in the stores send exactly this shape. The data classes are shared with the app, so
        // a renamed field would still round-trip in a typed test while dropping every released
        // app's data.
        val response = send(
            batch(
                """
                {"name":"payment",
                 "dimensions":{"wallet":"rayl","source":"scan","confirmation":"auto","outcome":"paid",
                               "error":"none","first_in_launch":"yes"},
                 "measures":{"qr_to_admitted_ms":12,"submit_to_outcome_ms":1840}},
                {"name":"launch","dimensions":{"wallet":"rayl","camera_variant":"control"},
                 "measures":{"process_to_first_frame_ms":640}}
                """
            )
        )

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(
            listOf(
                TelemetryRow(
                    receivedHour = 1_794_319_200,
                    platform = "android",
                    appVersion = "1.2.0",
                    osMajor = 16,
                    deviceModel = "Pixel 8",
                    name = "payment",
                    dimensions = mapOf(
                        "wallet" to "rayl",
                        "source" to "scan",
                        "confirmation" to "auto",
                        "outcome" to "paid",
                        "error" to "none",
                        "first_in_launch" to "yes"
                    ),
                    measures = mapOf("qr_to_admitted_ms" to 12L, "submit_to_outcome_ms" to 1840L)
                ),
                TelemetryRow(
                    receivedHour = 1_794_319_200,
                    platform = "android",
                    appVersion = "1.2.0",
                    osMajor = 16,
                    deviceModel = "Pixel 8",
                    name = "launch",
                    dimensions = mapOf("wallet" to "rayl", "camera_variant" to "control"),
                    measures = mapOf("process_to_first_frame_ms" to 640L)
                )
            ),
            stored
        )
    }

    @Test
    fun storesNothingTheCatalogueDoesNotDescribe() = telemetryTest {
        // The promise that no free text is collected rests on this: whatever a client puts into a
        // batch, only listed values and bounded numbers reach the database.
        val response = send(
            batch(
                """
                {"name":"payment",
                 "dimensions":{"wallet":"lnbc1500n1pexampleinvoice","outcome":"paid",
                               "recipient":"alice@example.com"},
                 "measures":{"submit_to_outcome_ms":900,"amount_sats":21000,
                             "qr_to_admitted_ms":-5,"admitted_to_prepared_ms":3600001}},
                {"name":"balance","dimensions":{"wallet":"rayl"},"measures":{"sats":500000}}
                """
            )
        )

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(1, stored.size)
        assertEquals("payment", stored.single().name)
        assertEquals(mapOf("outcome" to "paid"), stored.single().dimensions)
        assertEquals(mapOf("submit_to_outcome_ms" to 900L), stored.single().measures)
    }

    @Test
    fun dropsABatchWhoseDeviceModelIsNotAModelName() = telemetryTest {
        // The device model is the one value the catalogue cannot list, so its shape is what keeps
        // it from carrying anything else.
        val event = """{"name":"launch","dimensions":{"wallet":"rayl"}}"""

        assertEquals(HttpStatusCode.NoContent, send(batch(event, "a@b.example")).status)
        assertEquals(HttpStatusCode.NoContent, send(batch(event, "x".repeat(65))).status)
        assertEquals(HttpStatusCode.NoContent, send(batch(event, "")).status)

        assertEquals(emptyList(), stored)
    }

    @Test
    fun rejectsWhatIsNotABatch() = telemetryTest {
        assertEquals(HttpStatusCode.BadRequest, send("not json").status)
        assertEquals(HttpStatusCode.BadRequest, send("""{"platform":"android"}""").status)
        assertEquals(emptyList(), stored)
    }

    @Test
    fun rejectsABodyLargerThanAnyAppSends() = telemetryTest {
        val events = List(2_000) { """{"name":"launch","dimensions":{"wallet":"rayl"}}""" }

        val response = send(batch(events.joinToString(",")))

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals(emptyList(), stored)
    }

    @Test
    fun asksTheAppToRetryWhenStoringFails() = telemetryTest(
        store = TelemetryStore { error("database is away") }
    ) {
        val response = send(batch("""{"name":"launch","dimensions":{"wallet":"rayl"}}"""))

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun acceptsAndDropsBatchesWithoutADatabase() = telemetryTest(store = null) {
        val response = send(batch("""{"name":"launch","dimensions":{"wallet":"rayl"}}"""))

        assertEquals(HttpStatusCode.NoContent, response.status)
    }
}
