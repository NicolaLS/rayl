package xyz.lilsus.rayl.providers.blink.integration.blink

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import xyz.lilsus.raylsuite.core.model.WalletStartMode

/**
 * Rayl's Blink wallet against Blink's real backend on a local regtest stack (Blink's quickstart,
 * qa/test-plan.md §6.9). Each test gets a new Blink user and pays invoices of an outside
 * Lightning node, whose invoice says what really arrived; the user's Blink balance says what was
 * spent. Start the stack first: `tools/blink-stack/bin/up`.
 * Run: `./gradlew :providers:blink:integration:blink:walletIntegrationTest`.
 */
class BlinkWalletIntegrationTest {
    private val wallets = mutableListOf<BlinkWallet>()

    @AfterTest
    fun close() = wallets.forEach { it.close() }

    @Test
    fun paysAnInvoiceAndSpendsExactlyItsAmountPlusTheReportedFee() = runBlocking<Unit> {
        val user = newUser(fundedSats = 1_000)
        val wallet = connect(user)
        val invoice = outsideInvoice(sats = 100)

        val paid = assertIs<BlinkPaymentOutcome.Paid>(wallet.pay(invoice.request))

        assertNotNull(paid.preimageHex)
        assertEquals(100, received(invoice))
        val feeSats = (paid.feesPaidMsats ?: 0) / 1_000
        assertEquals(1_000 - 100 - feeSats, btcBalance(user))
    }

    @Test
    fun paysAnAmountlessInvoiceWithTheAmountEntered() = runBlocking<Unit> {
        val user = newUser(fundedSats = 1_000)
        val wallet = connect(user)
        val invoice = outsideInvoice(sats = 0)

        val outcome = wallet.pay(
            invoice.request,
            BlinkPaymentAmount.Bitcoin(milliSatoshis = 150_000)
        )

        assertIs<BlinkPaymentOutcome.Paid>(outcome)
        assertEquals(150, received(invoice))
    }

    @Test
    fun aPaidInvoiceIsNeverPaidTwice() = runBlocking<Unit> {
        val user = newUser(fundedSats = 1_000)
        val wallet = connect(user)
        val invoice = outsideInvoice(sats = 100)
        assertIs<BlinkPaymentOutcome.Paid>(wallet.pay(invoice.request))
        val balance = btcBalance(user)

        val second = wallet.pay(invoice.request)

        assertTrue(
            second is BlinkPaymentOutcome.AlreadyPaid ||
                second is BlinkPaymentOutcome.DefinitiveFailure,
            "Was $second"
        )
        assertEquals(balance, btcBalance(user))
        assertEquals(100, received(invoice))
    }

    @Test
    fun anEmptyWalletIsRefusedAsInsufficientBalance() = runBlocking<Unit> {
        val user = newUser(fundedSats = 0)
        val wallet = connect(user)
        val invoice = outsideInvoice(sats = 100)

        val outcome = wallet.pay(invoice.request)

        assertEquals(
            BlinkPaymentOutcome.DefinitiveFailure(
                BlinkApiError.BlinkError(BlinkErrorType.InsufficientBalance)
            ),
            outcome
        )
        assertEquals(0, received(invoice))
    }

    @Test
    fun anExpiredInvoiceFailsDefinitelyAndNothingIsSpent() = runBlocking<Unit> {
        // Blink doesn't check the expiry: it sends, and the receiver refuses ("rejected by
        // destination as invalid"). Rayl's payment flow refuses expired invoices before they reach
        // a wallet, so only an invoice expiring during those seconds gets here.
        val user = newUser(fundedSats = 1_000)
        val wallet = connect(user)
        val invoice = outsideInvoice(sats = 100, expirySecs = 1)
        delay(3_000)

        val outcome = wallet.pay(invoice.request)

        // LND drops expired invoices, so only the balance can say nothing was paid.
        assertIs<BlinkPaymentOutcome.DefinitiveFailure>(outcome)
        awaitBtcBalance(user, 1_000)
    }

    @Test
    fun aKeyThatCannotSendIsRefusedWhenConnecting() = runBlocking<Unit> {
        val user = newUser(fundedSats = 0, scopes = "READ")

        val refused = assertFailsWith<BlinkConnectionException> { connect(user) }

        assertEquals(BlinkConnectionError.RequiredPermissionsMissing, refused.error)
    }

    @Test
    fun aPaymentTheReceiverHoldsEndsPaidOnceItSettles() = runBlocking<Unit> {
        // Blink answers PENDING while the receiver holds the payment; Rayl must keep asking until
        // the payment ends, not report a result early. (A mutant that stops asking fails both.)
        val user = newUser(fundedSats = 1_000)
        val wallet = connect(user)
        val invoice = outsideHoldInvoice(sats = 100)

        val outcome = async(Dispatchers.IO) { wallet.pay(invoice.request) }
        awaitHeld(invoice)
        fixture("settle", invoice.preimage!!)

        assertIs<BlinkPaymentOutcome.Paid>(outcome.await())
        assertEquals(100, received(invoice))
    }

    @Test
    fun aPaymentTheReceiverHoldsEndsFailedWhenItIsCancelled() = runBlocking<Unit> {
        val user = newUser(fundedSats = 1_000)
        val wallet = connect(user)
        val invoice = outsideHoldInvoice(sats = 100)

        val outcome = async(Dispatchers.IO) { wallet.pay(invoice.request) }
        awaitHeld(invoice)
        fixture("cancel", invoice.paymentHash)

        assertIs<BlinkPaymentOutcome.DefinitiveFailure>(outcome.await())
        awaitBtcBalance(user, 1_000)
    }

    @Test
    fun paysFromTheStablesatsWallet() = runBlocking<Unit> {
        val user = newUser(fundedSats = 0)
        fixture("fund-usd", user.apiKey, "500")
        val wallet = connect(user)
        wallet.selectFundingWallet(BlinkFundingWallet(user.usdWalletId, BlinkWalletCurrency.USD))
        val invoice = outsideInvoice(sats = 100)

        val outcome = wallet.pay(invoice.request, fundingWallet = wallet.prepareFundingWallet())

        assertIs<BlinkPaymentOutcome.Paid>(outcome)
        assertEquals(100, received(invoice))
        assertTrue(usdBalance(user) < 500, "The USD wallet paid nothing")
    }

    @Test
    fun aPaymentWhoseReplyIsLostIsReportedAsUnknownAndIsNotPaidTwice() = runBlocking<Unit> {
        // PR-04, PR-03: the request reaches Blink and Blink pays, but the answer never arrives
        // (a dropped connection on the way back). "Failed" would invite paying again elsewhere.
        val user = newUser(fundedSats = 1_000)
        ReplyDroppingProxy(URI(apiUrl).port).use { proxy ->
            val wallet = connect(user, apiUrl = "http://127.0.0.1:${proxy.port}/graphql")
            val invoice = outsideInvoice(sats = 100)

            proxy.dropReplies = true
            val outcome = wallet.pay(invoice.request)

            assertIs<BlinkPaymentOutcome.StatusUnknown>(outcome)
            assertEquals(100, received(invoice))

            proxy.dropReplies = false
            val second = wallet.pay(invoice.request)

            assertIs<BlinkPaymentOutcome.AlreadyPaid>(second)
            assertEquals(100, received(invoice))
            awaitBtcBalanceAtMost(user, 900)
        }
    }

    private suspend fun connect(user: User, apiUrl: String = Companion.apiUrl): BlinkWallet {
        val wallet = BlinkWallet(
            apiClient = BlinkApiClient(BlinkApiClient.createBlinkApolloClient(apiUrl)),
            credentialStore = BlinkCredentialStore(TestSecureStringStore()),
            isNetworkAvailable = { true },
            startMode = WalletStartMode.Fresh
        )
        wallets += wallet
        wallet.connect(user.apiKey)
        return wallet
    }

    private suspend fun BlinkWallet.pay(
        invoice: String,
        amount: BlinkPaymentAmount? = null,
        fundingWallet: BlinkFundingWallet = prepareFundingWallet()
    ): BlinkPaymentOutcome = submitPayment(BlinkPaymentRequest(invoice, fundingWallet, amount))

    private data class User(val apiKey: String, val usdWalletId: String)

    private data class Invoice(val request: String, val paymentHash: String, val preimage: String?)

    private suspend fun newUser(fundedSats: Long, scopes: String = ""): User {
        val user = fixture("user", fundedSats.toString(), scopes)
        return User(user.string("apiKey"), user.string("usdWalletId"))
    }

    private suspend fun outsideInvoice(sats: Long, expirySecs: Int? = null): Invoice =
        fixture("invoice", sats.toString(), expirySecs?.toString().orEmpty()).toInvoice()

    private suspend fun outsideHoldInvoice(sats: Long): Invoice =
        fixture("hold-invoice", sats.toString()).toInvoice()

    /** Sats the outside node received for [invoice]. */
    private suspend fun received(invoice: Invoice): Long =
        fixture("received", invoice.paymentHash).getValue("paidSats").jsonPrimitive.long

    /**
     * Waits until Blink's payment reaches the outside node, then holds it long enough that Blink
     * answers PENDING (after 3 s on regtest, 45 s on mainnet).
     */
    private suspend fun awaitHeld(invoice: Invoice) {
        repeat(60) {
            if (fixture("received", invoice.paymentHash).string("state") == "ACCEPTED") {
                delay(6_000)
                return
            }
            delay(500)
        }
        error("Blink's payment never reached the outside node")
    }

    private suspend fun btcBalance(user: User): Long =
        fixture("balance", user.apiKey).getValue("btcSats").jsonPrimitive.long

    private suspend fun usdBalance(user: User): Long =
        fixture("balance", user.apiKey).getValue("usdCents").jsonPrimitive.long

    private suspend fun awaitBtcBalance(user: User, expected: Long) {
        repeat(30) {
            if (btcBalance(user) == expected) return
            delay(1_000)
        }
        assertEquals(expected, btcBalance(user), "Blink's balance")
    }

    /** Paid once: the balance went down by the amount and fee, not by twice that. */
    private suspend fun awaitBtcBalanceAtMost(user: User, max: Long) {
        val balance = btcBalance(user)
        assertTrue(balance in (max - 50)..max, "Blink's balance is $balance, expected about $max")
    }

    /**
     * Sits between Rayl and Blink. While [dropReplies] is set it still forwards every request to
     * Blink, but discards Blink's answer and closes the connection instead.
     */
    private class ReplyDroppingProxy(private val upstreamPort: Int) : AutoCloseable {
        @Volatile
        var dropReplies = false
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { relay(client) }
                }
            }
        }

        private fun relay(client: Socket) {
            val upstream = Socket(InetAddress.getLoopbackAddress(), upstreamPort)
            thread(isDaemon = true) {
                runCatching { client.getInputStream().copyTo(upstream.getOutputStream()) }
            }
            runCatching {
                val buffer = ByteArray(8_192)
                val replies = upstream.getInputStream()
                while (true) {
                    val read = replies.read(buffer)
                    if (read < 0 || dropReplies) break
                    client.getOutputStream().write(buffer, 0, read)
                }
            }
            client.close()
            upstream.close()
        }

        override fun close() = server.close()
    }

    private fun JsonObject.toInvoice() = Invoice(
        string("paymentRequest"),
        string("paymentHash"),
        get("preimage")?.jsonPrimitive?.content
    )

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    private suspend fun fixture(vararg args: String): JsonObject = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(listOf(fixtureScript.path) + args.filter(String::isNotEmpty))
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val errors = process.errorStream.bufferedReader().readText()
        check(process.waitFor(2, TimeUnit.MINUTES) && process.exitValue() == 0) {
            "Blink stack fixture `${args.first()}` failed: ${errors.trim()}. " +
                "Is the stack running? tools/blink-stack/bin/up"
        }
        Json.parseToJsonElement(output).jsonObject
    }

    private companion object {
        val apiUrl: String = System.getenv("RAYL_BLINK_API_URL") ?: "http://127.0.0.1:4475/graphql"
        val fixtureScript = File(
            "${System.getProperty("rayl.root") ?: "."}/tools/blink-stack/bin/fixture"
        )
    }
}
