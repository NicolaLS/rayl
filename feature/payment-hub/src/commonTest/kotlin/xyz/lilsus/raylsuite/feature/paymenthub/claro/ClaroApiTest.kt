package xyz.lilsus.raylsuite.feature.paymenthub.claro

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The stand-in answers as api.paquetes2.miclaro.com.sv and pl.btcpaths.com were observed to
 * (~/projects/claro, docs/bitcoin-flow.md and docs/btcpaths-api.md). No test reaches either.
 */
class ClaroApiTest {
    private class Request(val path: String, val body: JsonObject?, val headers: Headers) {
        val country get() = headers["country"]
    }

    /** Claro and BTCPaths in one engine. A path in [answers] replaces the usual answer. */
    private class Shop(answers: Map<String, Pair<HttpStatusCode, String>> = emptyMap(), retries: Boolean = false) {
        val answers = answers.toMutableMap()

        /** Sent along with whatever BTCPaths answers. */
        var retryAfter: String? = null
        var now = INVOICE_ISSUED_AT
        val requests = mutableListOf<Request>()
        val api = KtorClaroApi(
            client = HttpClient(
                MockEngine { request ->
                    val path = request.url.encodedPathAndQuery
                    requests += Request(
                        path,
                        request.body.toByteArray().decodeToString().takeIf(String::isNotEmpty)
                            ?.let { Json.parseToJsonElement(it).jsonObject },
                        request.headers
                    )
                    val (status, body) = this@Shop.answers[path] ?: (HttpStatusCode.OK to usual(path))
                    respond(
                        body,
                        status,
                        headers {
                            append(HttpHeaders.ContentType, "application/json")
                            retryAfter?.takeIf { path == CHECKOUT }?.let {
                                append(HttpHeaders.RetryAfter, it)
                            }
                        }
                    )
                }
            ) {
                // The app's client repeats failed requests; the order must not be one of them.
                if (retries) install(HttpRequestRetry) { retryOnServerErrors(maxRetries = 2) }
            },
            claroUrl = "https://claro.test",
            checkoutUrl = "https://btcpaths.test",
            nowSeconds = { now },
            checkoutPause = ClaroCheckoutPause()
        )

        fun paths() = requests.map { it.path }
        fun sent(path: String) = requests.single { it.path == path }

        private fun usual(path: String) = when (path) {
            "/config" -> CONFIG

            "/validateNumber" -> VALIDATION

            "/offerts/getOfferts" -> OFFERS

            "/paymentsMce" -> """{"idTransaction":"$TRANSACTION","redirectUrl":"https://card.test"}"""

            "/bitcoinPayment" ->
                """{"status":"SUCCESS","ok":true,"data":{"url":"https://if.btcpaths.com/$SLUG"}}"""

            CHECKOUT -> checkout()

            else -> error("Unexpected request to $path")
        }
    }

    @Test
    fun theCatalogueIsReadForTheLineClaroValidated() = runTest {
        val shop = Shop()

        val catalogue = (shop.api.catalogue(PHONE) as ClaroResult.Ok).value

        assertEquals(json("""{"claroNumber":"71234567"}"""), shop.sent("/validateNumber").body)
        assertEquals("SV", shop.sent("/validateNumber").country)
        // The line of business goes back as text, the company as it came.
        assertEquals(
            json("""{"phoneNumber":"71234567","LineOfBusiness":"1","companyId":7,"corp_ftu":false}"""),
            shop.sent("/offerts/getOfferts").body
        )
        assertEquals("SV", shop.sent("/offerts/getOfferts").country)
        // Claro's order within a category; a package listed twice appears once; what cannot be
        // ordered (no packId, the separate billing flow from USD 2,500) is left out.
        assertEquals(
            listOf(
                Triple("Internet 7 días", "6 GB + redes sociales · 7 días", 550L),
                Triple("Internet 30 días", "25 GB · 30 días", 1500L),
                Triple("Recarga $5", "Saldo de $5", 500L)
            ),
            catalogue.products.map { Triple(it.name, it.detail, it.priceCents) }
        )
        assertEquals(listOf(false, false, true), catalogue.products.map { it.topUp })
    }

    @Test
    fun aNumberClaroDoesNotSellToIsToldApartFromAnOutage() = runTest {
        suspend fun catalogue(path: String, status: HttpStatusCode, body: String) =
            Shop(mapOf(path to (status to body))).api.catalogue(PHONE)
        val ok = HttpStatusCode.OK

        // Not a Claro number, and a Claro line the web shop does not sell to.
        assertEquals(
            ClaroResult.NotEligible,
            catalogue("/validateNumber", ok, """{"body":{"data":{"AcknowledgementIndicator":"ERROR"}}}""")
        )
        assertEquals(ClaroResult.NotEligible, catalogue("/validateNumber", HttpStatusCode.BadRequest, "{}"))
        assertEquals(
            ClaroResult.NotEligible,
            catalogue(
                "/validateNumber",
                ok,
                """{"body":{"data":{"AcknowledgementIndicator":"SUCCESS","LineOfBusiness":"3"}}}"""
            )
        )
        // Claro is down, in maintenance, or answers something that is not a catalogue.
        assertEquals(
            ClaroResult.Unavailable,
            catalogue("/validateNumber", HttpStatusCode.InternalServerError, "{}")
        )
        assertEquals(
            ClaroResult.Unavailable,
            catalogue(
                "/config",
                ok,
                """{"body":{"data":[{"key":"LOB_MCE","value":"[1]"},""" +
                    """{"key":"MAINTENANCE_PACKAGES","value":"1"}]}}"""
            )
        )
        assertEquals(
            ClaroResult.Unavailable,
            catalogue("/offerts/getOfferts", ok, """{"data":{"AcknowledgementIndicator":"ERROR"}}""")
        )
    }

    @Test
    fun aPackageOrderSendsClaroItsOwnCatalogueEntryAndReturnsTheCheckoutInvoice() = runTest {
        val shop = Shop()
        val catalogue = (shop.api.catalogue(PHONE) as ClaroResult.Ok).value
        val pack = catalogue.products.first { it.name == "Internet 7 días" }

        val invoice = shop.api.invoice(catalogue.line, pack)

        assertEquals(ClaroResult.Ok(ClaroInvoice(INVOICE, 250_000)), invoice)
        val order = assertNotNull(shop.sent("/paymentsMce").body)
        assertNull(shop.sent("/paymentsMce").country)
        assertEquals(
            json(
                """{"xmei":"","phoneNumber":"71234567","amount":"5.50","channel":4,""" +
                    """"lineofbusiness":"1","packageCode":"9002",""" +
                    """"packageDescription":"Internet 7 días 6 GB + redes sociales"}"""
            ),
            JsonObject(order - "paymetPackageDescription")
        )
        // The entry as the shop serializes it: price rewritten, category added, icon dropped.
        assertEquals(
            json(
                """{"secuencia":1,"sku":"INT-7D","packId":"9002","packageName":"Internet 7 días",""" +
                    """"packageDescription":"6 GB + redes sociales","price":"5.50","currency":"USD",""" +
                    """"expiry":"7 días","payment":["TC","CS"],"categoryName":"Internet"}"""
            ),
            json(order.getValue("paymetPackageDescription").jsonPrimitive.content)
        )
        assertEquals(
            json(
                """{"numero":"71234567","transaccionId":"$TRANSACTION","precio":"5.50",""" +
                    """"package":"6 GB + redes sociales"}"""
            ),
            shop.sent("/bitcoinPayment").body
        )
        assertEquals("SV", shop.sent("/bitcoinPayment").country)
        // One look at the checkout, as its own page takes, and nothing after it.
        assertEquals(
            listOf("/paymentsMce", "/bitcoinPayment", CHECKOUT),
            shop.paths().drop(3)
        )
    }

    @Test
    fun everyRequestSaysWhoIsAskingAndNamesNoPageItCameFrom() = runTest {
        val shop = Shop()
        val catalogue = (shop.api.catalogue(PHONE) as ClaroResult.Ok).value
        shop.api.invoice(catalogue.line, catalogue.products.first())

        assertEquals(6, shop.requests.size)
        for (request in shop.requests) {
            val agent = assertNotNull(request.headers[HttpHeaders.UserAgent], request.path)
            // The app by name, and where to reach its maker.
            assertEquals(true, "Rayl" in agent && "rayl@nicolasusca.com" in agent, request.path)
            // It did not come from a page of theirs.
            assertNull(request.headers[HttpHeaders.Referrer], request.path)
        }
    }

    @Test
    fun afterBtcPathsTurnsTheAppAwayNothingIsOrderedForAWhile() = runTest {
        // MOB-154: asked on, BTCPaths blocked the whole network the phones were on.
        for (refusal in listOf(HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests)) {
            val shop = Shop(mapOf(CHECKOUT to (refusal to "{}")))
            val catalogue = (shop.api.catalogue(PHONE) as ClaroResult.Ok).value
            val product = catalogue.products.first()
            assertEquals(ClaroResult.Unavailable, shop.api.invoice(catalogue.line, product))
            shop.answers.clear()
            shop.requests.clear()

            // Neither an order at Claro, which would be left without its invoice, nor a question.
            shop.now += 14 * 60
            assertEquals(ClaroResult.Unavailable, shop.api.invoice(catalogue.line, product))
            assertEquals(emptyList(), shop.paths(), refusal.toString())
            // Its catalogue is Claro's alone to give.
            assertEquals(true, shop.api.catalogue(PHONE) is ClaroResult.Ok)
            shop.requests.clear()

            // A quarter of an hour on, an order is taken again. (The stand-in invoice has lapsed
            // by then, so it is known by its requests.)
            shop.now += 60
            shop.api.invoice(catalogue.line, product)
            assertEquals(listOf("/paymentsMce", "/bitcoinPayment", CHECKOUT), shop.paths())
        }
    }

    @Test
    fun btcPathsOwnWordOnHowLongToWaitIsKeptWithinAnHour() = runTest {
        /** Seconds until an order is taken again after a refusal that came with [retryAfter]. */
        suspend fun pause(retryAfter: String?): Long {
            val shop = Shop(mapOf(CHECKOUT to (HttpStatusCode.TooManyRequests to "{}")))
            shop.retryAfter = retryAfter
            val catalogue = (shop.api.catalogue(PHONE) as ClaroResult.Ok).value
            val product = catalogue.products.first()
            shop.api.invoice(catalogue.line, product)
            shop.answers.clear()
            val refusedAt = shop.now
            val steps = listOf(0L, 29, 30, 899, 900, 3599, 3600)
            return steps.first { after ->
                shop.requests.clear()
                shop.now = refusedAt + after
                shop.api.invoice(catalogue.line, product)
                shop.requests.isNotEmpty()
            }
        }

        assertEquals(30, pause("30"))
        assertEquals(900, pause(null))
        // A date, or nonsense, is no number of seconds.
        assertEquals(900, pause("Sat, 14 Nov 2026 23:00:00 GMT"))
        assertEquals(900, pause("-5"))
        assertEquals(3600, pause("86400"))
    }

    @Test
    fun aTopUpIsOrderedByItsPriceAlone() = runTest {
        val shop = Shop(mapOf(CHECKOUT to (HttpStatusCode.OK to checkout(usd = "5.00"))))
        val catalogue = (shop.api.catalogue(PHONE) as ClaroResult.Ok).value

        val invoice = shop.api.invoice(catalogue.line, catalogue.products.single { it.topUp })

        assertEquals(ClaroResult.Ok(ClaroInvoice(INVOICE, 250_000)), invoice)
        val order = assertNotNull(shop.sent("/paymentsMce").body)
        assertEquals(
            json("""{"xmei":"","phoneNumber":"71234567","amount":"5","channel":1,"lineofbusiness":"1"}"""),
            JsonObject(order - "paymetPackageDescription")
        )
    }

    @Test
    fun anOrderClaroDidNotAnswerIsNotPlacedAgain() = runTest {
        val shop = Shop(
            mapOf("/paymentsMce" to (HttpStatusCode.InternalServerError to "{}")),
            retries = true
        )
        val catalogue = (shop.api.catalogue(PHONE) as ClaroResult.Ok).value

        assertEquals(ClaroResult.Unavailable, shop.api.invoice(catalogue.line, catalogue.products.first()))

        // One order, and no bitcoin checkout for a transaction that may not exist.
        assertEquals(listOf("/paymentsMce"), shop.paths().drop(3))
    }

    @Test
    fun onlyACheckoutOnBtcPathsIsLookedUp() {
        assertEquals(SLUG, claroCheckoutSlug("https://if.btcpaths.com/$SLUG"))
        assertEquals(SLUG, claroCheckoutSlug("https://pl.btcpaths.com/?slug=$SLUG"))
        assertEquals(SLUG, claroCheckoutSlug("https://pl.btcpaths.com/api/v1/checkouts?slug=$SLUG"))
        val elsewhere = listOf(
            null,
            "",
            "http://if.btcpaths.com/$SLUG",
            "https://if.btcpaths.com.example.com/$SLUG",
            "https://example.com/?next=https://if.btcpaths.com/$SLUG",
            "https://if.btcpaths.com/$SLUG/../other",
            "https://if.btcpaths.com/${SLUG.dropLast(1)}",
            "https://if.btcpaths.com/$SLUG?slug=${SLUG.reversed()}"
        )
        for (url in elsewhere) assertNull(claroCheckoutSlug(url), url)
    }

    @Test
    fun aCheckoutInvoiceIsOnlyTakenWhenItIsTheOrderThatWasPlaced() {
        fun invoice(checkout: String = checkout(), phone: String = PHONE, priceCents: Long = 550, now: Long = INVOICE_ISSUED_AT) =
            claroCheckoutInvoice(json(checkout), phone, priceCents, now)
        val taken = ClaroInvoice(INVOICE, 250_000)

        assertEquals(taken, invoice())
        // JSON may write the same amounts differently.
        assertEquals(taken, invoice(checkout(btc = "2.5e-3", usd = "5.5")))
        assertEquals(taken, invoice(checkout(phone = "\"+50371234567\"")))

        // Paid or closed already.
        assertNull(invoice(checkout(status = "FINALIZED")))
        assertNull(invoice(checkout(status = "expired")))
        // Another price, another currency, another number.
        assertNull(invoice(priceCents = 500))
        assertNull(invoice(checkout(usd = "5.505")))
        assertNull(invoice(checkout(fiat = "EUR")))
        assertNull(invoice(phone = "71234568"))
        // The invoice is not over the bitcoin amount the checkout shows, or names none.
        assertNull(invoice(checkout(btc = "0.00250001")))
        assertNull(invoice(checkout(btc = "0.025")))
        assertNull(invoice(checkout(address = AMOUNTLESS_INVOICE)))
        assertNull(invoice(checkout(address = INVOICE.dropLast(1) + "q")))
        // No time left to pay: by the invoice's own expiry, and by BTCPaths' countdown.
        assertNull(invoice(now = INVOICE_ISSUED_AT + 31))
        assertNull(invoice(checkout(remaining = 30)))
    }

    @Test
    fun aNumberIsReadAsSalvadoransWriteIt() {
        for (written in listOf("71234567", "7123 4567", "7123-4567", "+503 7123 4567", "50371234567")) {
            assertEquals("71234567", claroPhone(written), written)
        }
        for (written in listOf("", "7123456", "712345678", "+502 7123 4567", "+1 503 7123 4567", "7123456a")) {
            assertNull(claroPhone(written), written)
        }
        assertEquals("7123 4567", claroPhoneLabel("71234567"))
    }

    private companion object {
        const val PHONE = "71234567"
        const val SLUG = "0123456789abcdef0123456789ABCDEF"
        const val TRANSACTION = "MCE503000123"
        const val CHECKOUT = "/api/v1/checkouts?slug=$SLUG"

        /** BOLT11's own example: 2,500 µBTC, issued at [INVOICE_ISSUED_AT], valid for a minute. */
        const val INVOICE = "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs" +
            "pp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpu9qrsgq" +
            "uk0rl77nj30yxdy8j9vdx85fkpmdla2087ne0xh8nhedh8w27kyke0lp53ut353s06fv3qfegext0eh0ymjpf39tu" +
            "ven09sam30g4vgpfna3rh"
        const val AMOUNTLESS_INVOICE = "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3" +
            "zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjype" +
            "h2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq" +
            "3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql"
        const val INVOICE_ISSUED_AT = 1_496_314_658L

        const val CONFIG = """{"body":{"data":[{"key":"LOB_MCE","value":"[1,2]"},""" +
            """{"key":"MAINTENANCE_GLOBAL_MCE","value":"0"},{"key":"MAINTENANCE_PACKAGES","value":"0"}]}}"""
        const val VALIDATION = """{"body":{"data":{"AcknowledgementIndicator":"SUCCESS",""" +
            """"LineOfBusiness":"01","companyId":7}}}"""

        private fun item(sequence: Int, sku: String, packId: String, name: String, about: String, price: String, expiry: String) =
            """{"secuencia":$sequence,"sku":"$sku","packId":"$packId","packageName":"$name",""" +
                """"packageDescription":"$about","price":$price,"currency":"USD","expiry":"$expiry",""" +
                """"payment":["TC","CS"],"redesSocialesIcon":"https://claro.test/icon.png"}"""

        val OFFERS = """{"data":{"package":[""" +
            """{"type":"P","category":[{"nombre":"Internet","items":[""" +
            item(2, "INT-30D", "9003", "Internet 30 días", "25 GB", "15", "30 días") + "," +
            item(1, "INT-7D", "9002", "Internet 7 días", "6 GB + redes sociales", "5.5", "7 días") + "," +
            item(3, "INT-NOPACK", "", "Sin código", "No se puede pedir", "3", "") + "," +
            item(4, "INT-CORP", "9099", "Corporativo", "Otro flujo", "2500", "") +
            """]},{"nombre":"Recomendados","items":[""" +
            item(1, "INT-7D", "9002", "Internet 7 días", "6 GB + redes sociales", "5.5", "7 días") +
            """]}]},""" +
            """{"type":"X","category":[{"nombre":"Otros","items":[""" +
            item(1, "OTHER", "1", "Desconocido", "Tipo que la tienda no vende", "1", "") +
            """]}]},""" +
            """{"type":"R","category":[{"nombre":"Recargas","items":[""" +
            item(1, "R5", "", "Recarga ${'$'}5", "Saldo de ${'$'}5", "\"5.00\"", "") +
            """]}]}]}}"""

        fun checkout(
            status: String = "ISSUED",
            address: String = INVOICE,
            phone: String = "71234567",
            usd: String = "5.50",
            fiat: String = "USD",
            btc: String = "0.0025",
            remaining: Int = 900
        ) = """{"id":"checkout","status":"$status","address":"$address","phone":$phone,""" +
            """"from_currency":{"amount":$usd,"symbol":"$fiat"},""" +
            """"to_currency":{"amount":$btc,"symbol":"BTC"},""" +
            """"expiration_minutes":15,"expiration_seconds":$remaining}"""

        fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    }
}
