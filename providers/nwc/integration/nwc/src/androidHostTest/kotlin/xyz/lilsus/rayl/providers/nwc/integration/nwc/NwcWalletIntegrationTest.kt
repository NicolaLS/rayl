package xyz.lilsus.rayl.providers.nwc.integration.nwc

import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import xyz.lilsus.raylsuite.core.model.WalletStartMode

/**
 * Rayl's NWC wallet against a real NWC wallet service on a local regtest stack (Core Lightning
 * with the cln-nip47 plugin and a local nostr relay, qa/test-plan.md §6.9). Rayl pays from the
 * `payer` node to invoices of the `receiver` node; the receiver's invoice says what really
 * arrived. Start the stack first: `tools/nwc-stack/bin/up`.
 * Run: `./gradlew :providers:nwc:integration:nwc:walletIntegrationTest`.
 */
class NwcWalletIntegrationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wallets = mutableListOf<NwcWallet>()

    @AfterTest
    fun close() = runBlocking<Unit> {
        wallets.forEach { runCatching { it.close() } }
        scope.cancel()
    }

    @Test
    fun paysAnInvoiceAndTheReceiverGetsExactlyItsAmount() = runBlocking<Unit> {
        val wallet = connect()
        val invoice = receiverInvoice(amountSats = 100)

        val paid = expectType<NwcPayOutcome.Settled>(wallet.pay(invoice))

        assertNotNull(paid.preimage)
        assertEquals(100_000, received(invoice))
    }

    @Test
    fun aPaidInvoiceIsNeverPaidTwiceOrLeftUncertain() = runBlocking<Unit> {
        // PR-03: wallets answer a second attempt differently (Core Lightning reports the earlier
        // success, Alby Hub refuses). Either is fine; "uncertain" or a second payment is not.
        val wallet = connect()
        val invoice = receiverInvoice(amountSats = 100)
        expectType<NwcPayOutcome.Settled>(wallet.pay(invoice))

        val second = wallet.pay(invoice)

        assertTrue(
            second is NwcPayOutcome.Settled || second is NwcPayOutcome.WalletRejected,
            "Was $second"
        )
        assertEquals(100_000, received(invoice))
    }

    @Test
    fun everyReplyArrivesForANewlyConnectedWallet() = runBlocking<Unit> {
        // MOB-115: against the hosted faucet relay, about 1 in 4 replies never reached Rayl. With a
        // local relay that delivers every event, a lost reply would be Rayl's.
        val outcomes = (1..10).map {
            val wallet = connect(label = "reply-$it-${System.nanoTime()}")
            wallet.pay(receiverInvoice(amountSats = 10))
        }

        assertTrue(outcomes.all { it is NwcPayOutcome.Settled }, "Outcomes: $outcomes")
    }

    /** A new NWC connection to the payer node, connected the way the app connects a pasted URI. */
    private suspend fun connect(
        budgetSats: Long = 100_000,
        label: String = "rayl-it-${System.nanoTime()}"
    ): NwcWallet {
        val uri = helper(
            "GET",
            "/get-nwc-uri?node=payer&label=$label&budget_msat=${budgetSats * 1_000}"
        )
            .getValue("uri").jsonPrimitive.content
        val wallet = createNwcWallet(
            secureSettings = TestSecureStringStore(),
            scope = scope,
            isNetworkAvailable = { true },
            startMode = WalletStartMode.Fresh
        )
        wallets += wallet
        wallet.connect(uri, alias = "local")
        return wallet
    }

    private class Invoice(val bolt11: String, val paymentHash: String)

    private suspend fun NwcWallet.pay(invoice: Invoice, amountMsats: Long? = null) =
        payInvoice(invoice.bolt11, amountMsats, invoice.paymentHash)

    private fun receiverInvoice(amountSats: Long?, expirySecs: Long? = null): Invoice {
        val body = buildString {
            append(
                """{"node":"receiver","amount_msat":"${amountSats?.let {
                    it * 1_000
                } ?: "any"}""""
            )
            append(""","label":"rayl-it-${System.nanoTime()}"""")
            expirySecs?.let { append(""","expiry":$it""") }
            append("}")
        }
        val json = helper("POST", "/create-invoice", body)
        return Invoice(
            json.getValue("bolt11").jsonPrimitive.content,
            json.getValue("paymentHash").jsonPrimitive.content
        )
    }

    /** Millisatoshis the receiver got for [invoice]: what really arrived. */
    private fun received(invoice: Invoice): Long {
        val status =
            helper(
                "POST",
                "/invoice-status",
                """{"node":"receiver","paymentHash":"${invoice.paymentHash}"}"""
            )
        if (status["settled"]?.jsonPrimitive?.booleanOrNull != true) return 0
        return status["amountReceivedMsat"]?.jsonPrimitive?.longOrNull ?: 0
    }

    private fun helper(method: String, path: String, body: String? = null): JsonObject {
        val request = HttpRequest.newBuilder(URI("$HELPER$path"))
            .method(
                method,
                body?.let(HttpRequest.BodyPublishers::ofString)
                    ?: HttpRequest.BodyPublishers.noBody()
            )
            .header("content-type", "application/json")
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (error: ConnectException) {
            throw IllegalStateException(
                "The local NWC stack isn't running: start tools/nwc-stack/bin/up",
                error
            )
        }
        check(response.statusCode() == 200) {
            "Helper $path answered ${response.statusCode()}: ${response.body()}"
        }
        return Json.parseToJsonElement(response.body()).jsonObject
    }

    private companion object {
        val HELPER = System.getenv("TEST_WALLET_SERVICE_URL") ?: "http://127.0.0.1:8082"
        val http: HttpClient = HttpClient.newHttpClient()
    }
}

/** [assertIs] that says what the value was, so a failure in CI explains itself. */
private inline fun <reified T> expectType(value: Any?): T = assertIs<T>(value, "Was $value")
