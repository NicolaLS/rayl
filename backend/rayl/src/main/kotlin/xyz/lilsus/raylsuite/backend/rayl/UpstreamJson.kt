package xyz.lilsus.raylsuite.backend.rayl

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The most an upstream's answer may weigh. The largest real one is about 60 kB; a compromised
 * upstream must not be able to fill the heap, so nothing past this is read.
 */
internal const val MAX_UPSTREAM_BODY_BYTES = 1024 * 1024

/**
 * How deep an upstream's JSON may nest. Real answers nest four levels; the parser recurses per
 * level, so unbounded nesting would overflow its stack.
 */
internal const val MAX_UPSTREAM_JSON_DEPTH = 32

private const val MAX_LOG_LINE_LENGTH = 200

/** The client for every upstream call. The engine is a parameter so tests can stand in for it. */
internal fun upstreamHttpClient(engine: HttpClientEngine) = HttpClient(engine) {
    // A source that answers with a redirect could point the backend at an internal address.
    followRedirects = false
    install(HttpTimeout) { requestTimeoutMillis = 10_000 }
    install(UserAgent) { agent = "rayl-backend" }
}

/** Fetches JSON from an upstream that is not trusted to answer within reason. */
internal suspend fun HttpClient.getJson(
    url: String,
    request: HttpRequestBuilder.() -> Unit = {}
): JsonElement = prepareGet(url, request).execute { response ->
    val host = response.call.request.url.host
    check(response.status.isSuccess()) { "HTTP ${response.status.value} from $host" }
    val body = response.bodyAsChannel().readRemaining(MAX_UPSTREAM_BODY_BYTES + 1L).readByteArray()
    check(body.size <= MAX_UPSTREAM_BODY_BYTES) { "$host sent more than the allowed bytes" }
    parseUpstreamJson(body.decodeToString())
}

internal fun parseUpstreamJson(text: String): JsonElement {
    check(text.nestsAtMost(MAX_UPSTREAM_JSON_DEPTH)) { "JSON nests too deep" }
    return Json.parseToJsonElement(text)
}

/** Counts open brackets outside strings; whether the text is valid JSON is the parser's concern. */
private fun String.nestsAtMost(limit: Int): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (char in this) {
        when {
            escaped -> escaped = false

            inString -> when (char) {
                '\\' -> escaped = true
                '"' -> inString = false
            }

            char == '"' -> inString = true

            char == '{' || char == '[' -> if (++depth > limit) return false

            char == '}' || char == ']' -> depth--
        }
    }
    return true
}

/**
 * A failure as one bounded log line. Messages can quote what an upstream sent, which must not be
 * able to start a line of its own in the log.
 */
internal fun Throwable.logLine(): String {
    val message = message.orEmpty()
        .take(MAX_LOG_LINE_LENGTH)
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString("")
    return "${this::class.simpleName}: $message"
}
