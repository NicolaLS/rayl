package xyz.lilsus.raylsuite.backend.rayl

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.core.raylapi.BitcoinPrices
import xyz.lilsus.raylsuite.core.raylapi.RaylApi
import xyz.lilsus.raylsuite.core.raylapi.RaylApiError

private val REFRESH_INTERVAL = 30.seconds
private val TELEMETRY_MAINTENANCE_INTERVAL = 24.hours
private val TELEMETRY_MAINTENANCE_RETRY = 1.minutes

/** At most this many usage-data batches per minute, over all senders. */
private const val TELEMETRY_BATCHES_PER_MINUTE = 600

/**
 * Railway sets `PORT`; the container image sets `HOST=0.0.0.0`. `DATABASE_URL` names the Postgres
 * database for usage data; without it, batches are accepted and dropped.
 */
fun main() {
    val host = System.getenv("HOST") ?: "127.0.0.1"
    val port = (System.getenv("PORT") ?: "8080").toInt()
    require(port in 1..65_535) { "PORT must be between 1 and 65535" }
    val http = HttpClient(OkHttp) {
        install(HttpTimeout) { requestTimeoutMillis = 10_000 }
        install(UserAgent) { agent = "rayl-backend" }
    }
    val board = PriceBoard(defaultPriceSources(http))
    val telemetry = System.getenv("DATABASE_URL")?.takeIf(String::isNotBlank)?.let { url ->
        PostgresTelemetryStore(PostgresTelemetryStore.connect(url))
    }
    if (telemetry == null) println("DATABASE_URL is not set: usage data is accepted and dropped")
    embeddedServer(CIO, host = host, port = port) {
        raylModule(telemetry, bitcoinPrices = board::current)
        launch {
            while (true) {
                board.refresh()
                delay(REFRESH_INTERVAL)
            }
        }
        if (telemetry != null) launch { maintainTelemetry(telemetry) }
    }.start(wait = true)
}

/** Creates the tables, then folds and expires usage data once a day. Prices never wait for it. */
private suspend fun maintainTelemetry(telemetry: PostgresTelemetryStore) {
    var tablesCreated = false
    while (true) {
        val interval = try {
            if (!tablesCreated) {
                telemetry.createTables()
                tablesCreated = true
            }
            telemetry.maintain(nowSeconds = System.currentTimeMillis() / 1_000)
            TELEMETRY_MAINTENANCE_INTERVAL
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            println("Usage data maintenance failed: ${failure::class.simpleName}")
            TELEMETRY_MAINTENANCE_RETRY
        }
        delay(interval)
    }
}

fun Application.raylModule(
    telemetry: TelemetryStore? = null,
    nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
    bitcoinPrices: () -> BitcoinPrices?
) {
    install(ContentNegotiation) { json() }
    install(RateLimit) {
        register(TELEMETRY_RATE_LIMIT) {
            rateLimiter(limit = TELEMETRY_BATCHES_PER_MINUTE, refillPeriod = 1.minutes)
        }
    }
    routing {
        telemetryRoute(telemetry, nowSeconds)
        get(RaylApi.BITCOIN_PRICES_PATH) {
            val prices = bitcoinPrices()
            if (prices == null) {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(HttpStatusCode.ServiceUnavailable, RaylApiError("prices_unavailable"))
            } else {
                call.response.header(HttpHeaders.CacheControl, "public, max-age=15")
                call.respond(prices)
            }
        }
        get("/health") { call.respondText("ok") }
    }
}
