package xyz.lilsus.raylsuite.backend.rayl

import io.ktor.client.engine.okhttp.OkHttp
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
import xyz.lilsus.raylsuite.core.raylapi.BitcoinPriceHistory
import xyz.lilsus.raylsuite.core.raylapi.BitcoinPrices
import xyz.lilsus.raylsuite.core.raylapi.RaylApi
import xyz.lilsus.raylsuite.core.raylapi.RaylApiError

private val REFRESH_INTERVAL = 30.seconds
private val TELEMETRY_MAINTENANCE_INTERVAL = 24.hours
private val TELEMETRY_MAINTENANCE_RETRY = 1.minutes

/** At most this many usage-data batches per minute, over all senders. */
private const val TELEMETRY_BATCHES_PER_MINUTE = 600

/** At most this many from one sender. An app sends one when it is left and one after a scan. */
internal const val TELEMETRY_BATCHES_PER_CLIENT_MINUTE = 20

/**
 * Railway sets `PORT`; the container image sets `HOST=0.0.0.0`. `DATABASE_URL` names the Postgres
 * database for usage data; without it, batches are accepted and dropped.
 */
fun main() {
    val host = System.getenv("HOST") ?: "127.0.0.1"
    val port = (System.getenv("PORT") ?: "8080").toInt()
    require(port in 1..65_535) { "PORT must be between 1 and 65535" }
    val http = upstreamHttpClient(OkHttp.create())
    val board = PriceBoard(defaultPriceSources(http))
    val history = PriceHistoryBoard(KrakenHistorySource(http))
    val database = System.getenv("DATABASE_URL")?.takeIf(String::isNotBlank)
        ?.let(PostgresTelemetryStore::connect)
    val telemetry = database?.let(::PostgresTelemetryStore)
    val feedback = database?.let(PostgresFeedbackStore::from)
    if (telemetry ==
        null
    ) {
        println(
            "DATABASE_URL is not set: usage data is accepted and dropped, feedback is refused"
        )
    }
    embeddedServer(CIO, host = host, port = port) {
        raylModule(
            telemetry,
            feedback = feedback,
            bitcoinPriceHistory = history::history,
            bitcoinPrices = board::current
        )
        launch {
            while (true) {
                board.refresh()
                delay(REFRESH_INTERVAL)
            }
        }
        if (telemetry != null && feedback != null) launch { maintainTelemetry(telemetry, feedback) }
    }.start(wait = true)
}

/** Creates the tables, then folds and expires usage data once a day. Prices never wait for it. */
private suspend fun maintainTelemetry(
    telemetry: PostgresTelemetryStore,
    feedback: PostgresFeedbackStore
) {
    var tablesCreated = false
    while (true) {
        val interval = try {
            if (!tablesCreated) {
                telemetry.createTables()
                feedback.createTables()
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
    feedback: FeedbackStore? = null,
    bitcoinPriceHistory: suspend (String) -> BitcoinPriceHistory? = { null },
    bitcoinPrices: () -> BitcoinPrices?
) {
    install(ContentNegotiation) { json() }
    install(RateLimit) {
        register(TELEMETRY_CLIENT_RATE_LIMIT) {
            rateLimiter(limit = TELEMETRY_BATCHES_PER_CLIENT_MINUTE, refillPeriod = 1.minutes)
            requestKey { it.clientKey() }
        }
        register(TELEMETRY_RATE_LIMIT) {
            rateLimiter(limit = TELEMETRY_BATCHES_PER_MINUTE, refillPeriod = 1.minutes)
        }
        register(FEEDBACK_CLIENT_RATE_LIMIT) {
            rateLimiter(limit = FEEDBACK_PER_CLIENT_MINUTE, refillPeriod = 1.minutes)
            requestKey { it.clientKey() }
        }
        register(FEEDBACK_RATE_LIMIT) {
            rateLimiter(limit = FEEDBACK_PER_MINUTE, refillPeriod = 1.minutes)
        }
    }
    routing {
        telemetryRoute(telemetry, nowSeconds)
        feedbackRoute(feedback, nowSeconds)
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
        get(RaylApi.BITCOIN_PRICE_HISTORY_PATH) {
            val currency = call.request.queryParameters[RaylApi.BITCOIN_PRICE_HISTORY_CURRENCY]
                ?.uppercase()
            val history = currency?.let { bitcoinPriceHistory(it) }
            if (history == null) {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(
                    if (currency in PriceHistoryBoard.CURRENCIES) {
                        HttpStatusCode.ServiceUnavailable
                    } else {
                        HttpStatusCode.NotFound
                    },
                    RaylApiError("price_history_unavailable")
                )
            } else {
                call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
                call.respond(history)
            }
        }
        get("/health") { call.respondText("ok") }
    }
}
