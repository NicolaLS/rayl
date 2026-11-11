package xyz.lilsus.raylsuite.feature.paymenthub.ppq

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class PpqApiTest {
    private val invoice = "lnbc10u1p" + "q".repeat(40)

    private fun api(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "{}",
        onRequest: suspend (String, String?, String) -> Unit = { _, _, _ -> }
    ) = KtorPpqApi(
        client = HttpClient(
            MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                onRequest(
                    request.url.toString(),
                    request.headers[HttpHeaders.Authorization],
                    request.body.toByteArray().decodeToString()
                )
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ),
        baseUrl = "https://ppq.test"
    )

    @Test
    fun theBalanceIsAskedForWithTheKeyAsBearerToken() = runTest {
        val requests = mutableListOf<Pair<String, String?>>()
        val api = api(body = """{"balance":12.5}""") { url, auth, _ -> requests += url to auth }

        assertEquals(PpqResult.Ok(12.5), api.balanceUsd("sk-test"))
        assertEquals<List<Pair<String, String?>>>(
            listOf("https://ppq.test/credits/balance" to "Bearer sk-test"),
            requests
        )
    }

    @Test
    fun theBalanceIsFoundWhereverTheResponsePutsIt() {
        // The response body is not documented, so these are the layouts the reader must survive.
        fun balance(body: String) = parsePpqBalance(Json.parseToJsonElement(body))
        assertEquals(12.5, balance("""{"balance":12.5}"""))
        assertEquals(0.0, balance("""{"balance":"0"}"""))
        assertEquals(3.25, balance("""{"status":"success","data":{"credit_id":"x","balance":"3.25"}}"""))
        assertEquals(7.0, balance("""{"credit_balance":7,"total":99}"""))
        // The plain field wins over a longer name that merely contains it.
        assertEquals(1.0, balance("""{"bonus_balance":5,"balance":1}"""))
        assertNull(balance("""{"error":"nope"}"""))
        assertNull(balance("""{"balance":-4}"""))
        assertNull(balance("""{"balance":null}"""))
    }

    @Test
    fun aTopUpAsksForUsDollarsAndReturnsTheInvoice() = runTest {
        var sent = ""
        var url = ""
        val api = api(body = """{"invoice_id":"abc","lightning_invoice":"$invoice","amount":5}""") { u, _, body ->
            url = u
            sent = body
        }

        assertEquals(PpqResult.Ok(invoice), api.createLightningTopUp("sk-test", 5.0))
        assertEquals("https://ppq.test/topup/create/btc-lightning", url)
        assertEquals(
            Json.parseToJsonElement("""{"amount":5.0,"currency":"USD"}"""),
            Json.parseToJsonElement(sent)
        )
    }

    @Test
    fun theInvoiceIsFoundWhereverTheResponsePutsIt() {
        fun found(body: String) = parsePpqInvoice(Json.parseToJsonElement(body))
        assertEquals(invoice, found("""{"data":{"checkout":{"payment_request":"$invoice"}}}"""))
        assertEquals(invoice, found("""{"id":"lnbc","links":["https://x.test"],"bolt11":"lightning:$invoice"}"""))
        assertEquals(invoice.uppercase(), found("""{"invoice":"${invoice.uppercase()}"}"""))
        assertNull(found("""{"address":"bc1qxyz","invoice_id":"abc"}"""))
    }

    @Test
    fun aRejectedKeyIsToldApartFromAServiceThatCannotBeUsed() = runTest {
        assertEquals(PpqResult.InvalidKey, api(HttpStatusCode.Unauthorized, """{"error":"Invalid API key"}""").balanceUsd("k"))
        assertEquals(PpqResult.InvalidKey, api(HttpStatusCode.Forbidden).createLightningTopUp("k", 5.0))
        assertEquals(PpqResult.Unreachable, api(HttpStatusCode.BadRequest).balanceUsd("k"))
        assertEquals(PpqResult.Unreachable, api(body = "<html>").balanceUsd("k"))
        assertEquals(PpqResult.Unreachable, api(body = """{"ok":true}""").createLightningTopUp("k", 5.0))

        val offline = KtorPpqApi(HttpClient(MockEngine { throw IllegalStateException("offline") }), "https://ppq.test")
        assertEquals(PpqResult.Unreachable, offline.balanceUsd("k"))
    }

    @Test
    fun cancellationIsNotReportedAsAnUnreachableService() = runTest {
        val cancelled = KtorPpqApi(HttpClient(MockEngine { throw CancellationException("left the Hub") }), "https://ppq.test")
        assertFailsWith<CancellationException> { cancelled.balanceUsd("k") }
    }
}
