package xyz.lilsus.raylsuite.backend.rayl

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.raylapi.RaylApi
import xyz.lilsus.raylsuite.core.raylapi.TelemetryBatch
import xyz.lilsus.raylsuite.core.raylapi.TelemetryCatalog

/**
 * One stored event of Rayl's anonymous usage data. [receivedHour] is the arrival time cut to the
 * hour (Unix seconds), so a row cannot be matched to a request in an access log. There is no
 * column for an identifier or an address.
 */
data class TelemetryRow(
    val receivedHour: Long,
    val platform: String,
    val appVersion: String,
    val osMajor: Int,
    val deviceModel: String,
    val name: String,
    val dimensions: Map<String, String>,
    val measures: Map<String, Long>
)

fun interface TelemetryStore {
    suspend fun insert(rows: List<TelemetryRow>)
}

internal val TELEMETRY_RATE_LIMIT = RateLimitName("telemetry")
internal const val TELEMETRY_MAX_BODY_BYTES = 64L * 1024

private const val SECONDS_PER_HOUR = 3_600L
private val telemetryJson = Json { ignoreUnknownKeys = true }

/**
 * Accepts what the catalogue describes and drops the rest. Answers 204 either way: the app has
 * nothing to do with the difference, and an old or new app must not start retrying. Without a
 * [store], everything is dropped.
 */
internal fun Route.telemetryRoute(store: TelemetryStore?, nowSeconds: () -> Long) {
    rateLimit(TELEMETRY_RATE_LIMIT) {
        route(RaylApi.TELEMETRY_PATH) {
            install(RequestBodyLimit) { bodyLimit { TELEMETRY_MAX_BODY_BYTES } }
            post {
                val batch = try {
                    telemetryJson.decodeFromString(TelemetryBatch.serializer(), call.receiveText())
                } catch (_: SerializationException) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                } catch (_: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }
                val accepted = TelemetryCatalog.sanitize(batch)
                if (accepted != null && store != null) {
                    val receivedHour = nowSeconds() / SECONDS_PER_HOUR * SECONDS_PER_HOUR
                    val rows = accepted.events.map { event ->
                        TelemetryRow(
                            receivedHour = receivedHour,
                            platform = accepted.platform,
                            appVersion = accepted.appVersion,
                            osMajor = accepted.osMajor,
                            deviceModel = accepted.deviceModel,
                            name = event.name,
                            dimensions = event.dimensions,
                            measures = event.measures
                        )
                    }
                    try {
                        store.insert(rows)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Exception) {
                        // Nothing of the request is logged.
                        println("Storing usage data failed: ${failure::class.simpleName}")
                        call.respond(HttpStatusCode.ServiceUnavailable)
                        return@post
                    }
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
