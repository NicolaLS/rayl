package xyz.lilsus.raylsuite.integration.lnurl

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.core.network.NetworkConnectivity
import xyz.lilsus.raylsuite.core.payment.LnurlError
import xyz.lilsus.raylsuite.core.payment.LnurlPayParams
import xyz.lilsus.raylsuite.core.payment.LnurlResult

class KtorLnurlPayClientTest {
    @Test
    fun aLightningAddressIsFetchedFromItsLowercaseWellKnownUrl() = runTest {
        // LUD-16: user names are lowercase. A capitalised address, as phone keyboards type it, is
        // normalised when parsed (MOB-89); the tag stays part of the user name.
        var requestedHost: String? = null
        var requestedPath: String? = null
        val engine =
            MockEngine { request ->
                requestedHost = request.url.host
                requestedPath = request.url.encodedPath
                respond(
                    content =
                        """
                            {
                              "callback": "https://example.com/callback",
                              "maxSendable": 1000,
                              "minSendable": 1000,
                              "metadata": "[[\"text/plain\",\"Payment\"]]",
                              "tag": "payRequest"
                            }
                        """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        val client =
            KtorLnurlPayClient(
                networkConnectivity = AlwaysOnline,
                client = HttpClient(engine),
                resolveHostAddresses = { listOf(byteArrayOf(8, 8, 8, 8)) }
            )

        val result =
            client.fetchPayParams(
                assertNotNull(LightningAddress.parse("Alice+Tips@Example.com"))
            )

        assertIs<LnurlResult.Success<*>>(result)
        assertEquals("example.com", requestedHost)
        assertEquals("/.well-known/lnurlp/alice+tips", requestedPath)
    }

    @Test
    fun cancellationIsPropagatedInsteadOfBecomingAnError() = runTest {
        // A cancelled payment session must not surface as an LNURL error afterwards.
        val client = cancellingClient()

        assertFailsWith<CancellationException> {
            client.fetchPayParams("https://example.com/lnurl")
        }
        assertFailsWith<CancellationException> {
            client.requestInvoice(
                callback = "https://example.com/callback",
                amountMsats = 1_000,
                comment = null
            )
        }
    }

    @Test
    fun rejectsUnsafeDestinationsAndDnsAnswersBeforeRequesting() = runTest {
        var requestCount = 0
        val client = KtorLnurlPayClient(
            networkConnectivity = AlwaysOnline,
            client = HttpClient(
                MockEngine {
                    requestCount++
                    respond("unused")
                }
            ),
            resolveHostAddresses = { listOf(byteArrayOf(127, 0, 0, 1)) }
        )
        listOf(
            "http://example.com/pay", "https://localhost/pay", "https://127.0.0.1/pay",
            "https://2130706433/pay", "https://0x7f000001/pay", "https://[::1]/pay",
            "https://user:password@example.com/pay", "https://example.com/pay#fragment",
            "https://wallet.local/pay", "https://wallet.onion/pay", "https://example.com/pay"
        ).forEach { endpoint ->
            assertIs<LnurlResult.Error>(client.fetchPayParams(endpoint))
            assertIs<LnurlResult.Error>(client.requestInvoice(endpoint, 1_000, null))
        }
        assertEquals(0, requestCount)
        assertTrue(isPublicLnurlAddress(byteArrayOf(8, 8, 8, 8)))
        assertFalse(isPublicLnurlAddress(byteArrayOf(100, 64, 0, 1)))
        assertFalse(isPublicLnurlAddress(byteArrayOf(169.toByte(), 254.toByte(), 0, 1)))
        val nat64 = byteArrayOf(0, 0x64, 0xff.toByte(), 0x9b.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8)
        assertTrue(isPublicLnurlAddress(nat64))
        nat64[12] = 127
        assertFalse(isPublicLnurlAddress(nat64))
        assertFalse(isPublicLnurlAddress(ByteArray(16).apply { this[0] = 0xfd.toByte() }))
        client.close()
    }

    @Test
    fun rejectsRedirectsAndOversizedResponsesWithoutTrustingContentLength() = runTest {
        var requestCount = 0
        val redirectClient = client(
            MockEngine {
                requestCount++
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://example.com/next"))
            }
        )
        assertIs<LnurlResult.Error>(redirectClient.fetchPayParams("https://example.com/pay"))
        assertEquals(1, requestCount)
        redirectClient.close()

        val oversizedClient = client(MockEngine { respond(" ".repeat(256 * 1024 + 1)) })
        assertIs<LnurlResult.Error>(oversizedClient.fetchPayParams("https://example.com/pay"))
        assertIs<LnurlResult.Error>(oversizedClient.requestInvoice("https://example.com/pay", 1_000, null))
        oversizedClient.close()

        // Deep nesting inside an otherwise valid response, after text with an escaped quote: the
        // depth guard must still see it (a bare array would be rejected for its shape anyway).
        val nested = "[".repeat(64) + "0" + "]".repeat(64)
        val nestedResponse = PAY_PARAMS.replace("\"tag\"", "\"note\": \"say \\\"hi\\\"\", \"x\": $nested, \"tag\"")
        val nestedClient = client(MockEngine { respond(nestedResponse) })
        assertIs<LnurlResult.Error>(nestedClient.fetchPayParams("https://example.com/pay"))
        nestedClient.close()
    }

    @Test
    fun rejectsMalformedFieldsAndMetadata() = runTest {
        val valid = Json.parseToJsonElement(PAY_PARAMS) as JsonObject
        val invalid = listOf(
            JsonObject(valid - "tag"),
            JsonObject(valid + ("tag" to JsonPrimitive("PAYREQUEST"))),
            JsonObject(valid + ("minSendable" to JsonPrimitive(1.5))),
            JsonObject(valid + ("maxSendable" to JsonPrimitive("9223372036854775808"))),
            JsonObject(valid + ("callback" to JsonPrimitive("http://example.com/callback"))),
            JsonObject(valid + ("commentAllowed" to JsonPrimitive(-1))),
            JsonObject(valid + ("metadata" to JsonPrimitive("[]"))),
            JsonObject(valid + ("metadata" to JsonPrimitive("[[\"text/plain\", {}]]"))),
            JsonObject(valid + ("metadata" to JsonPrimitive("[[\"text/plain\",\" \" ]]"))),
            JsonObject(valid + ("metadata" to JsonPrimitive("[[\"text/plain\",\"one\"],[\"text/plain\",\"two\"]]"))),
            // Limits a payment can't satisfy (S4).
            JsonObject(valid + ("minSendable" to JsonPrimitive(2_000)) + ("maxSendable" to JsonPrimitive(1_000))),
            JsonObject(valid + ("minSendable" to JsonPrimitive(0))),
            JsonObject(valid + ("maxSendable" to JsonPrimitive(0))),
            JsonObject(valid + ("minSendable" to JsonPrimitive(-1_000)))
        )
        invalid.forEach { response ->
            val client = client(MockEngine { respond(response.toString()) })
            val result = assertIs<LnurlResult.Error>(client.fetchPayParams("https://example.com/pay"))
            assertIs<LnurlError.Protocol>(result.error)
            client.close()
        }
    }

    @Test
    fun invoiceRequestSendsOnlyTheApprovedAmountAndComment() = runTest {
        // A server-supplied amount in the callback URL must never reach the invoice request.
        var parameters: Parameters? = null
        val client = client(
            MockEngine { request ->
                parameters = request.url.parameters
                respond("{\"pr\":\"lnbc1invoice\"}")
            }
        )

        client.requestInvoice("https://example.com/callback?amount=5&comment=old", 1_000, "approved comment")

        assertEquals(listOf("1000"), parameters?.getAll("amount"))
        assertEquals(listOf("approved comment"), parameters?.getAll("comment"))
        client.close()
    }

    @Test
    fun aHostResolvingToAnyPrivateAddressOrToNoneIsNeverContacted() = runTest {
        // DNS rebinding: one public answer must not hide a private one (PR-05, S4).
        listOf(
            listOf(byteArrayOf(8, 8, 8, 8), byteArrayOf(10, 0, 0, 1)),
            emptyList()
        ).forEach { answers ->
            var requests = 0
            val client = KtorLnurlPayClient(
                networkConnectivity = AlwaysOnline,
                client = HttpClient(
                    MockEngine {
                        requests++
                        respond(PAY_PARAMS)
                    }
                ),
                resolveHostAddresses = { answers }
            )

            assertIs<LnurlResult.Error>(client.fetchPayParams("https://example.com/pay"))
            assertIs<LnurlResult.Error>(client.requestInvoice("https://example.com/callback", 1_000, null))
            assertEquals(0, requests, "answers $answers")
            client.close()
        }
    }

    @Test
    fun aCallbackWithoutAnInvoiceIsAnError() = runTest {
        listOf("{}", "{\"pr\":\"\"}", "{\"pr\":42}").forEach { response ->
            val client = client(MockEngine { respond(response) })

            assertIs<LnurlResult.Error>(client.requestInvoice("https://example.com/callback", 1_000, null), response)
            client.close()
        }
    }

    @Test
    fun bracketsInsideTextDoNotCountTowardsTheNestingLimit() = runTest {
        // A merchant description may contain brackets and escaped quotes; only real nesting is
        // limited.
        val text = "[".repeat(100) + "\\\"" + "{".repeat(100)
        val response = PAY_PARAMS.replace("\"tag\"", "\"description\": \"$text\", \"tag\"")
        val client = client(MockEngine { respond(response) })

        assertIs<LnurlResult.Success<*>>(client.fetchPayParams("https://example.com/pay"))
        client.close()
    }

    @Test
    fun aServerThatAllowsNoCommentIsStillPayable() = runTest {
        // commentAllowed = 0 means "no comment", not an invalid response.
        val client = client(MockEngine { respond(PAY_PARAMS.replace("\"tag\"", "\"commentAllowed\": 0, \"tag\"")) })

        val params = assertIs<LnurlResult.Success<LnurlPayParams>>(client.fetchPayParams("https://example.com/pay")).data
        assertEquals(0, params.commentAllowed)
        client.close()
    }

    @Test
    fun remoteFailureReasonsAreNotShown() = runTest {
        // The reason text is controlled by the remote server.
        val client = client(MockEngine { respond("{\"status\":\"ERROR\",\"reason\":\"untrusted response contents\"}") })

        val result = assertIs<LnurlResult.Error>(client.requestInvoice("https://example.com/callback", 1_000, null))

        assertEquals(LnurlError.Protocol("LNURL service rejected the request"), result.error)
        assertNull(result.cause)
        client.close()
    }

    private fun client(engine: MockEngine): KtorLnurlPayClient = KtorLnurlPayClient(
        networkConnectivity = AlwaysOnline,
        client = HttpClient(engine),
        resolveHostAddresses = { listOf(byteArrayOf(8, 8, 8, 8)) }
    )

    private fun cancellingClient(): KtorLnurlPayClient {
        val engine = MockEngine { throw CancellationException("cancelled") }
        return KtorLnurlPayClient(
            networkConnectivity = AlwaysOnline,
            client = HttpClient(engine),
            resolveHostAddresses = { listOf(byteArrayOf(8, 8, 8, 8)) }
        )
    }

    private data object AlwaysOnline : NetworkConnectivity {
        override fun isNetworkAvailable(): Boolean = true
    }
}

private const val PAY_PARAMS = """{
    "callback": "https://example.com/callback",
    "maxSendable": 1000,
    "minSendable": 1000,
    "metadata": "[[\"text/plain\",\"Payment\"]]",
    "tag": "payRequest"
}"""
