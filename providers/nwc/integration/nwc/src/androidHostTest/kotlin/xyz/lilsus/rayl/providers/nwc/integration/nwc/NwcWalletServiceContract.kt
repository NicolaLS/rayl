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
 * Rayl's NWC wallet against real NWC wallet services on a local regtest stack with a local nostr
 * relay (qa/test-plan.md §6.9). The same tests run against two independent implementations, so a
 * failure on only one of them points at that service rather than at Rayl:
 * - [ClnNip47WalletIntegrationTest]: Core Lightning with the cln-nip47 plugin;
 * - [AlbyHubWalletIntegrationTest]: Alby Hub, the most used NWC wallet service.
 *
 * Rayl pays from the service's node to invoices of the `receiver` node (Core Lightning); the
 * receiver's invoice says what really arrived. Start the stack first: `tools/nwc-stack/bin/up`.
 * Run: `./gradlew :providers:nwc:integration:nwc:walletIntegrationTest`.
 */
abstract class NwcWalletServiceContract(private val service: NwcWalletService) {
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

    @Test
    fun aWalletWhoseRelayRestartedStillPays() = runBlocking<Unit> {
        // PR-17: the relay drops every connection (a restart, a network switch). The app stays in
        // the foreground, so nothing rebuilds the client; the next payment must still work.
        val wallet = connect()
        val probe = connect()
        expectType<NwcPayOutcome.Settled>(wallet.pay(receiverInvoice(amountSats = 10)))

        relay("restart")
        awaitRelay(probe)
        val invoice = receiverInvoice(amountSats = 100)
        val outcome = wallet.pay(invoice)

        expectType<NwcPayOutcome.Settled>(outcome)
        assertEquals(100_000, received(invoice))
    }

    @Test
    fun aWalletPaysAfterTheAppWasInTheBackground() = runBlocking<Unit> {
        // PR-17: the client is released in the background and rebuilt on return (stale sockets
        // after backgrounding once broke NWC on iOS). Payments must work after that cycle.
        val wallet = connect()
        expectType<NwcPayOutcome.Settled>(wallet.pay(receiverInvoice(amountSats = 10)))

        wallet.onAppForegroundChanged(false)
        delay(5_000)
        wallet.onAppForegroundChanged(true)
        val invoice = receiverInvoice(amountSats = 100)

        expectType<NwcPayOutcome.Settled>(wallet.pay(invoice))
        assertEquals(100_000, received(invoice))
    }

    @Test
    fun nothingIsReportedPaidWhileTheRelayIsDown() = runBlocking<Unit> {
        val wallet = connect()
        val probe = connect()
        val invoice = receiverInvoice(amountSats = 100)
        try {
            relay("stop")

            val outcome = wallet.pay(invoice)

            assertTrue(outcome !is NwcPayOutcome.Settled, "Was $outcome")
            assertEquals(0, received(invoice))
            println("Relay down: $outcome")
        } finally {
            relay("start")
            awaitRelay(probe)
        }
    }

    @Test
    fun paysTheAmountEnteredForAnAmountlessInvoice() = runBlocking<Unit> {
        // PR-01: an amountless invoice pays exactly what the user entered.
        val wallet = connect()
        val invoice = receiverInvoice(amountSats = null)

        expectType<NwcPayOutcome.Settled>(wallet.pay(invoice, amountMsats = 150_000))

        assertEquals(150_000, received(invoice))
    }

    @Test
    fun aPaymentTheWalletCannotRouteIsRefusedDefinitely() = runBlocking<Unit> {
        // More than the service's channel holds (1M sats); the connection's budget allows it.
        val wallet = connect(budgetSats = 10_000_000)
        val invoice = receiverInvoice(amountSats = 2_000_000)

        expectType<NwcPayOutcome.WalletRejected>(wallet.pay(invoice))
        assertEquals(0, received(invoice))
    }

    @Test
    fun aPaymentAboveTheConnectionsBudgetIsRefusedDefinitely() = runBlocking<Unit> {
        // NWC connections usually carry a spending budget; going over it is a common refusal.
        val wallet = connect(budgetSats = 50)
        val invoice = receiverInvoice(amountSats = 100)

        val refused = expectType<NwcPayOutcome.WalletRejected>(wallet.pay(invoice))

        assertEquals("QUOTA_EXCEEDED", refused.code)
        assertEquals(0, received(invoice))
    }

    @Test
    fun anExpiredInvoiceIsRefusedDefinitely() = runBlocking<Unit> {
        val wallet = connect()
        val invoice = receiverInvoice(amountSats = 100, expirySecs = 1)
        delay(3_000)

        expectType<NwcPayOutcome.WalletRejected>(wallet.pay(invoice))
        assertEquals(0, received(invoice))
    }

    private fun relay(command: String) = docker(command, "$PROJECT-nostr-1")

    private fun docker(command: String, container: String) {
        val process = ProcessBuilder("docker", command, container)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "docker $command failed: $output" }
    }

    /**
     * Waits until the relay and the wallet service answer again, through [probe]: a connection
     * made before the relay went away, like the one under test. A new connection isn't proof:
     * Alby Hub subscribes new connections at once but re-subscribes existing ones later, and
     * requests published before that are lost (the relay doesn't keep NWC events).
     * Core Lightning's NIP-47 plugin doesn't reconnect to a restarted relay on its own, so it is
     * restarted too; that's the test environment, not Rayl.
     */
    private suspend fun awaitRelay(probe: NwcWallet) {
        if (service.restartAfterRelayRestart) docker("restart", "$PROJECT-${service.container}-1")
        repeat(10) {
            if (probe.pay(receiverInvoice(amountSats = 1)) is NwcPayOutcome.Settled) return
            delay(2_000)
        }
        error("The NWC stack didn't answer again after the relay restart")
    }

    /** A new NWC connection to the service, connected the way the app connects a pasted URI. */
    private suspend fun connect(
        budgetSats: Long = 100_000,
        label: String = "rayl-it-${System.nanoTime()}"
    ): NwcWallet {
        val uri = helper(
            "GET",
            "/get-nwc-uri?node=${service.node}&label=$label&budget_msat=${budgetSats * 1_000}"
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
        val PROJECT = System.getenv("RAYL_NWC_PROJECT") ?: "rayl-nwc"
        val http: HttpClient = HttpClient.newHttpClient()
    }
}

/** [assertIs] that says what the value was, so a failure in CI explains itself. */
private inline fun <reified T> expectType(value: Any?): T = assertIs<T>(value, "Was $value")

/** An NWC wallet service of the local stack that can pay. */
enum class NwcWalletService(
    /** The helper's name for it (`/get-nwc-uri?node=`). */
    val node: String,
    /** Its Docker Compose service. */
    val container: String,
    /** Whether it has to be restarted to reconnect to a restarted relay. */
    val restartAfterRelayRestart: Boolean
) {
    ClnNip47(node = "payer", container = "payer", restartAfterRelayRestart = true),
    AlbyHub(node = "albyhub", container = "albyhub", restartAfterRelayRestart = false)
}

class ClnNip47WalletIntegrationTest : NwcWalletServiceContract(NwcWalletService.ClnNip47)

class AlbyHubWalletIntegrationTest : NwcWalletServiceContract(NwcWalletService.AlbyHub)
