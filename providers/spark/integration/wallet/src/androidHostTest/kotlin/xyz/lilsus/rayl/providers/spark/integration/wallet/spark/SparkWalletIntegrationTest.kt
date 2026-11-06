package xyz.lilsus.rayl.providers.spark.integration.wallet.spark

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import xyz.lilsus.rayl.providers.spark.application.AppBootstrapConfig
import xyz.lilsus.rayl.providers.spark.application.AppEnvironment
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPayOutcome
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPaymentClient
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPaymentProblem
import xyz.lilsus.rayl.providers.spark.application.payment.SparkQuoteResult
import xyz.lilsus.rayl.providers.spark.application.wallet.SparkSdkSession
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletCredential
import xyz.lilsus.rayl.tools.regtest.bank.PaymentRecord
import xyz.lilsus.rayl.tools.regtest.bank.PaymentRecord.Companion.received
import xyz.lilsus.rayl.tools.regtest.bank.PaymentRecord.Companion.sent
import xyz.lilsus.rayl.tools.regtest.bank.RegtestBank
import xyz.lilsus.rayl.tools.regtest.bank.knownDefect

/**
 * Spark as an external wallet against the real Breez SDK on Lightspark's hosted Regtest
 * (qa/test-plan.md §6.9), connected through [BreezSparkSdkConnector] as the app does after a
 * phrase import. The host tests stub the SDK; these check that Rayl's code and the real SDK agree.
 * Run: `./gradlew :providers:spark:integration:wallet:walletIntegrationTest`.
 */
class SparkWalletIntegrationTest {
    private lateinit var bank: RegtestBank
    private lateinit var home: File
    private val mnemonic = RegtestBank.newMnemonic()
    private var session: SparkSdkSession? = null
    private val client: SparkPaymentClient get() = checkNotNull(session).paymentClient

    @BeforeTest
    fun openBank() = runBlocking<Unit> {
        bank = RegtestBank.open()
        home = Files.createTempDirectory("rayl-spark-it").toFile()
    }

    @AfterTest
    fun disconnect() = runBlocking<Unit> {
        runCatching { session?.disconnect() }
        bank.reclaim(mnemonic, File(home, "reclaim"))
        runCatching { bank.close() }
        home.deleteRecursively()
    }

    @Test
    fun paysAnInvoiceAndReportsTheFeeItQuoted() = runBlocking<Unit> {
        importWallet(funded = 300)

        val quote = expectType<SparkQuoteResult.Quoted>(client.quote(bank.invoice(100), null)).quote
        val paid = expectType<SparkPayOutcome.Paid>(client.pay(quote))

        assertEquals(quote.feeSats, paid.feeSats)
        assertHistory(received(300), sent(100, paid.feeSats))
    }

    @Test
    fun paysTheAmountEnteredForAnAmountlessInvoice() = runBlocking<Unit> {
        // PR-01: an amountless invoice pays exactly what the user entered.
        importWallet(funded = 300)

        val quote = expectType<SparkQuoteResult.Quoted>(client.quote(bank.invoice(null), 150)).quote
        val paid = expectType<SparkPayOutcome.Paid>(client.pay(quote))

        assertHistory(received(300), sent(150, paid.feeSats))
    }

    @Test
    fun aPaidInvoiceIsRefusedAsAlreadyPaidAndNotPaidAgain() = runBlocking<Unit> {
        // PR-03, MOB-90: the settlement turns AlreadyPaid into "already paid" success.
        importWallet(funded = 300)
        val invoice = bank.invoice(100)
        val paid = expectType<SparkPayOutcome.Paid>(payOrRefusal(invoice))

        assertEquals(SparkPaymentProblem.AlreadyPaid, payOrRefusal(invoice))
        assertHistory(received(300), sent(100, paid.feeSats))
    }

    @Test
    fun anEmptyWalletIsRefusedForInsufficientFunds() = runBlocking<Unit> {
        // MOB-101: shown as "outcome unknown" while the stubs assumed a different SDK error.
        importWallet(funded = 0)

        assertEquals(SparkPaymentProblem.InsufficientFunds, payOrRefusal(bank.invoice(100)))
        assertHistory()
    }

    @Test
    fun anExpiredInvoiceIsRefusedAsExpired() = runBlocking<Unit> {
        importWallet(funded = 300)
        val invoice = bank.invoice(100, expirySecs = 1u)
        delay(3_000)

        assertEquals(SparkPaymentProblem.Expired, payOrRefusal(invoice))
        // MOB-113: Breez still moves amount + fee into a pending send that the sending session
        // doesn't see. Rayl's flow no longer sends expired invoices, so this shows Breez's part.
        knownDefect("MOB-113") { assertHistory(received(300)) }
    }

    /**
     * Imports the phrase as the app does: Rayl's connector with empty storage. A separate session
     * funds the wallet first, like a wallet the user funded elsewhere, so every test also checks
     * that an import finds its funds (L13).
     */
    private suspend fun importWallet(funded: Long) {
        if (funded > 0) {
            val funding = RegtestBank.connectWallet(mnemonic, File(home, "funding"))
            try {
                bank.fund(funding, funded)
            } finally {
                funding.disconnect()
            }
        }
        session = BreezSparkSdkConnector.connect(
            credential = WalletCredential.fromMnemonic(mnemonic),
            storageDirectory = File(home, "rayl").absolutePath,
            freshStorage = true,
            bootstrapConfig = AppBootstrapConfig(AppEnvironment.DEBUG)
        )
    }

    /** The outcome if paid, otherwise the problem, whether refused while quoting or sending. */
    private suspend fun payOrRefusal(invoice: String): Any =
        when (val result = client.quote(invoice, null)) {
            is SparkQuoteResult.Quoted -> when (val outcome = client.pay(result.quote)) {
                is SparkPayOutcome.Failed -> outcome.problem
                else -> outcome
            }

            SparkQuoteResult.InsufficientFunds -> SparkPaymentProblem.InsufficientFunds

            is SparkQuoteResult.Rejected -> result.problem

            SparkQuoteResult.NotReady -> result
        }

    /**
     * Ends Rayl's session and checks the payments Breez holds for the wallet, as a fresh session
     * (an app restart, a new phone) sees them. Each test's last step.
     */
    private suspend fun assertHistory(vararg expected: PaymentRecord) {
        session?.disconnect()
        session = null
        val reader = RegtestBank.connectWallet(mnemonic, File(home, "reader"))
        try {
            RegtestBank.assertPayments(reader, *expected)
        } finally {
            reader.disconnect()
        }
    }
}

/** [assertIs] that says what the value was, so a failure in CI explains itself. */
private inline fun <reified T> expectType(value: Any?): T = assertIs<T>(value, "Was $value")
