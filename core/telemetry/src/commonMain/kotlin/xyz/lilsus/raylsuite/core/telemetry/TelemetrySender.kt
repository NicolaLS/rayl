package xyz.lilsus.raylsuite.core.telemetry

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.raylapi.RaylApi
import xyz.lilsus.raylsuite.core.raylapi.TelemetryBatch
import xyz.lilsus.raylsuite.core.raylapi.TelemetryCatalog
import xyz.lilsus.raylsuite.core.raylapi.TelemetryEvent

/** The device facts every batch carries. [osMajor] is the API level on Android. */
data class TelemetryDevice(val platform: String, val osMajor: Int, val model: String)

/** Posts batches to the Rayl backend. The client is created on first use, never at launch. */
class TelemetrySender(
    private val device: TelemetryDevice,
    private val appVersion: String,
    private val baseUrl: String = RaylApi.BASE_URL,
    client: () -> HttpClient
) {
    private val client by lazy(client)

    /** False when the batch should be tried again: the backend was unreachable or unwell. */
    suspend fun send(events: List<TelemetryEvent>): Boolean {
        val batch = TelemetryBatch(
            platform = device.platform,
            appVersion = appVersion,
            osMajor = device.osMajor,
            deviceModel = device.model,
            events = events
        )
        return try {
            val response = client.post(baseUrl + RaylApi.TELEMETRY_PATH) {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(TelemetryBatch.serializer(), batch))
            }
            // A batch the backend refuses will be refused again.
            response.status.value < 500
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
    }
}

/** The device model reduced to the characters the backend accepts. */
internal fun String.asTelemetryDeviceModel(): String = replace(Regex("[^A-Za-z0-9 ._,()+/-]"), "-")
    .trim()
    .take(TelemetryCatalog.MAX_DEVICE_MODEL_LENGTH)
    .ifEmpty { "unknown" }
