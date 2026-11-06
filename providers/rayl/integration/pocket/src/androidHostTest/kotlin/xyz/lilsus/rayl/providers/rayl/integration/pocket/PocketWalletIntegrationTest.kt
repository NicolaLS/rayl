package xyz.lilsus.rayl.providers.rayl.integration.pocket

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import xyz.lilsus.rayl.tools.regtest.bank.PaymentRecord
import xyz.lilsus.rayl.tools.regtest.bank.PaymentRecord.Companion.received
import xyz.lilsus.rayl.tools.regtest.bank.PaymentRecord.Companion.sent
import xyz.lilsus.rayl.tools.regtest.bank.RegtestBank
import xyz.lilsus.rayl.tools.regtest.bank.knownDefect

/**
 * Rayl's wallet (the pocket) against the real Breez SDK on Lightspark's hosted Regtest
 * (qa/test-plan.md §6.9), through the same connector the app uses. The host tests stub the SDK;
 * these check that Rayl's code and the real SDK agree.
 * Run: `./gradlew :providers:rayl:integration:pocket:walletIntegrationTest`.
 */
class PocketWalletIntegrationTest {
    private lateinit var bank: RegtestBank
    private lateinit var storage: File
    private val key = PocketKey(RegtestBank.newMnemonic())
    private lateinit var pocket: PocketWallet

    @BeforeTest
    fun connect() = runBlocking<Unit> {
        bank = RegtestBank.open()
        storage = File(Files.createTempDirectory("rayl-pocket-it").toFile(), "sdk")
        pocket = connector().connect(key)
    }

    @AfterTest
    fun disconnect() = runBlocking<Unit> {
        // Usually already disconnected by the test's last step; a second call is harmless.
        runCatching { pocket.disconnect() }
        runCatching { bank.close() }
        storage.parentFile.deleteRecursively()
    }

    @Test
    fun addedFundsArriveAsAPaymentOfTheirInvoice() = runBlocking<Unit> {
        // Add funds: the app shows the received amount when the invoice it created is paid.
        val received = fund(300)

        assertEquals(300, received.amountSats)
        assertEquals(300, awaitBalance { it == 300L })
    }

    @Test
    fun paysAnInvoiceAndReportsTheFeeItQuoted() = runBlocking<Unit> {
        fund(300)

        val quote = assertIs<PocketQuoteResult.Quoted>(pocket.quote(bank.invoice(100), null)).quote
        val paid = assertIs<PocketPayOutcome.Paid>(pocket.pay(quote))

        assertEquals(quote.feeSats, paid.feeSats)
        assertHistory(received(300), sent(100, paid.feeSats))
    }

    @Test
    fun paysTheAmountEnteredForAnAmountlessInvoice() = runBlocking<Unit> {
        // PR-01: an amountless invoice pays exactly what the user entered.
        fund(300)

        val quote = assertIs<PocketQuoteResult.Quoted>(pocket.quote(bank.invoice(null), 150)).quote
        val paid = assertIs<PocketPayOutcome.Paid>(pocket.pay(quote))

        assertHistory(received(300), sent(150, paid.feeSats))
    }

    @Test
    fun aPaidInvoiceIsRefusedAsAlreadyPaidAndNotPaidAgain() = runBlocking<Unit> {
        // PR-03, MOB-90: the settlement turns AlreadyPaid into "already paid" success.
        fund(300)
        val invoice = bank.invoice(100)
        val paid = assertIs<PocketPayOutcome.Paid>(payOrRefusal(invoice))

        assertEquals(PocketPaymentProblem.AlreadyPaid, payOrRefusal(invoice))
        assertHistory(received(300), sent(100, paid.feeSats))
    }

    @Test
    fun anEmptyWalletIsRefusedForInsufficientFunds() = runBlocking<Unit> {
        // MOB-101: shown as "outcome unknown" while the stubs assumed a different SDK error.
        assertEquals(PocketPaymentProblem.InsufficientFunds, payOrRefusal(bank.invoice(100)))
        assertHistory()
    }

    @Test
    fun anExpiredInvoiceIsRefusedAsExpired() = runBlocking<Unit> {
        fund(300)
        val invoice = bank.invoice(100, expirySecs = 1u)
        delay(3_000)

        assertEquals(PocketPaymentProblem.Expired, payOrRefusal(invoice))
        // MOB-113: Breez still moves amount + fee into a pending send that the sending session
        // doesn't see. Rayl's flow no longer sends expired invoices, so this shows Breez's part.
        knownDefect("MOB-113") { assertHistory(received(300)) }
    }

    @Test
    fun aReinstalledAppFindsItsFundsWithTheSameKeyAndPays() = runBlocking<Unit> {
        // PR-09: after a reinstall or on a new phone, the passkey gives the same key and the SDK
        // starts with empty storage; the funds must be found again.
        fund(300)
        pocket.disconnect()
        storage.deleteRecursively()

        pocket = connector().connect(key)
        assertEquals(300, awaitBalance { it == 300L })
        val quote = assertIs<PocketQuoteResult.Quoted>(pocket.quote(bank.invoice(100), null)).quote
        val paid = assertIs<PocketPayOutcome.Paid>(pocket.pay(quote))

        assertHistory(received(300), sent(100, paid.feeSats))
    }

    private fun connector() = BreezPocketConnector(
        PocketConfig(PocketNetwork.Regtest, breezApiKey = null),
        storageIsEmpty = { !storage.exists() || storage.list().isNullOrEmpty() },
        storageDirectory = { storage.apply { mkdirs() }.absolutePath }
    )

    /** Funds the pocket through its own invoice, as "add funds" does. */
    private suspend fun fund(sats: Long): PocketEvent.Received = coroutineScope {
        val invoice = pocket.createInvoice(sats, "Rayl integration test")
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(60_000) {
                pocket.events.filterIsInstance<PocketEvent.Received>().first {
                    it.invoice == invoice
                }
            }
        }
        bank.pay(invoice)
        received.await()
    }

    /** The outcome if paid, otherwise the problem, whether refused while quoting or sending. */
    private suspend fun payOrRefusal(invoice: String): Any =
        when (val result = pocket.quote(invoice, null)) {
            is PocketQuoteResult.Quoted -> when (val outcome = pocket.pay(result.quote)) {
                is PocketPayOutcome.Failed -> outcome.problem
                else -> outcome
            }

            PocketQuoteResult.InsufficientFunds -> PocketPaymentProblem.InsufficientFunds

            is PocketQuoteResult.Rejected -> result.problem

            PocketQuoteResult.NotReady -> result
        }

    /** The balance the app shows, once it meets [condition] (it settles shortly after a change). */
    private suspend fun awaitBalance(condition: (Long) -> Boolean): Long {
        var balance = pocket.balanceSats()
        repeat(60) {
            if (condition(balance)) return balance
            delay(1_000)
            balance = pocket.balanceSats()
        }
        return balance
    }

    /**
     * Ends the pocket's session and checks the payments Breez holds for its key, as a fresh session
     * (an app restart, a new phone) sees them. Each test's last step.
     */
    private suspend fun assertHistory(vararg expected: PaymentRecord) {
        pocket.disconnect()
        val reader = RegtestBank.connectWallet(key.phrase, File(storage.parentFile, "reader"))
        try {
            RegtestBank.assertPayments(reader, *expected)
        } finally {
            reader.disconnect()
        }
    }
}
