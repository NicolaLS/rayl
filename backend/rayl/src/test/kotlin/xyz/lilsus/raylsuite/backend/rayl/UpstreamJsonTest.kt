package xyz.lilsus.raylsuite.backend.rayl

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/** What a compromised price source can send must cost that source only, never the service. */
class UpstreamJsonTest {
    private fun answering(body: String) = HttpClient(MockEngine { respond(body) })

    @Test
    fun readsAnOrdinaryAnswer() = runTest {
        assertEquals(
            mapOf("USD" to 83_428.0),
            MempoolSource(answering("""{"time":1790705710,"USD":83428}""")).fetch()
        )
    }

    @Test
    fun anAnswerThatNeverEndsIsGivenUpOnAtTheLimit() = runTest {
        var written = 0L
        val http = HttpClient(
            MockEngine {
                val endless = ByteChannel()
                backgroundScope.launch {
                    val chunk = ByteArray(64 * 1024) { ' '.code.toByte() }
                    while (true) {
                        endless.writeFully(chunk)
                        endless.flush()
                        written += chunk.size
                    }
                }
                respond(endless)
            }
        )

        assertFailsWith<IllegalStateException> { MempoolSource(http).fetch() }
        assertTrue(written < 4L * MAX_UPSTREAM_BODY_BYTES, "read $written bytes of an endless body")
    }

    @Test
    fun anAnswerJustOverTheLimitIsRefusedAndOneAtTheLimitIsRead() = runTest {
        val padding = " ".repeat(MAX_UPSTREAM_BODY_BYTES - """{"USD":1}""".length)
        assertEquals(mapOf("USD" to 1.0), MempoolSource(answering("""{"USD":1}$padding""")).fetch())
        assertFailsWith<IllegalStateException> {
            MempoolSource(answering("""{"USD":1}$padding """)).fetch()
        }
    }

    @Test
    fun deeplyNestedJsonFailsLikeAnyBadAnswer() = runTest {
        // Deep enough to overflow the parser's stack, which is an Error and not an Exception.
        val nested = "[".repeat(200_000)
        assertFailsWith<IllegalStateException> { MempoolSource(answering(nested)).fetch() }
        assertFailsWith<IllegalStateException> {
            KrakenHistorySource(answering("""{"result":$nested""")).fetch("USD", sinceSeconds = 0)
        }
    }

    @Test
    fun bracketsInsideStringsAreNotNesting() {
        val brackets = "[{".repeat(MAX_UPSTREAM_JSON_DEPTH)
        parseUpstreamJson("""{"note":"$brackets \" $brackets"}""")
        parseUpstreamJson("[".repeat(MAX_UPSTREAM_JSON_DEPTH) + "]".repeat(MAX_UPSTREAM_JSON_DEPTH))
        assertFailsWith<IllegalStateException> {
            parseUpstreamJson(
                "[".repeat(MAX_UPSTREAM_JSON_DEPTH + 1) + "]".repeat(MAX_UPSTREAM_JSON_DEPTH + 1)
            )
        }
    }

    @Test
    fun aRedirectFromASourceIsNotFollowed() = runTest {
        val asked = mutableListOf<String>()
        val http = upstreamHttpClient(
            MockEngine { request ->
                asked += request.url.host
                if (request.url.host == "mempool.space") {
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(HttpHeaders.Location, "http://rayl.railway.internal/prices")
                    )
                } else {
                    respond("""{"USD":1}""")
                }
            }
        )

        assertFailsWith<IllegalStateException> { MempoolSource(http).fetch() }
        assertEquals(listOf("mempool.space"), asked)
    }

    @Test
    fun aFailureIsLoggedAsOneBoundedLine() {
        val line = IllegalStateException(
            "kraken error [\"x\"]\n2026 forged line\r" + "a".repeat(500)
        )
            .logLine()
        assertFalse(line.any { it == '\n' || it == '\r' })
        assertTrue(line.startsWith("IllegalStateException: kraken error"))
        assertTrue(line.length < 250)
    }
}
