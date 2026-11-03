package xyz.lilsus.rayl.tools.regtest

import breez_sdk_spark.BreezSdk
import breez_sdk_spark.ConnectRequest
import breez_sdk_spark.GetInfoRequest
import breez_sdk_spark.Network
import breez_sdk_spark.PaymentRequest
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.PrepareSendPaymentRequest
import breez_sdk_spark.PrepareSendPaymentResponse
import breez_sdk_spark.ReceivePaymentMethod
import breez_sdk_spark.ReceivePaymentRequest
import breez_sdk_spark.Seed
import breez_sdk_spark.SendPaymentMethod
import breez_sdk_spark.SendPaymentOptions
import breez_sdk_spark.SendPaymentRequest
import breez_sdk_spark.connect
import breez_sdk_spark.defaultConfig
import fr.acinq.bitcoin.MnemonicCode
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * What Rayl's wallet code assumes about the Breez SDK (qa/stub-assumptions.md, A1–A4),
 * checked against the real SDK on Lightspark's hosted Regtest. The host tests stub the SDK with
 * these assumptions; if Breez changes one, these fail and the stubs must follow.
 *
 * Needs the network and a funded regtest bank (`tools/regtest/regtest status`).
 * Run: `./gradlew :tools:regtest:regtestContractTest`
 */
class BreezSdkContractTest {
    private lateinit var bank: BreezSdk
    private lateinit var payer: BreezSdk
    private lateinit var payerHome: File
    private lateinit var payerMnemonic: String

    @BeforeTest
    fun openWallets() = runBlocking<Unit> {
        bank = open(bankMnemonic(), File(bankHome(), "sdk"))
        payerHome = Files.createTempDirectory("rayl-contract").toFile()
        payerMnemonic =
            MnemonicCode.toMnemonics(
                ByteArray(16).also(SecureRandom()::nextBytes)
            ).joinToString(" ")
        payer = open(payerMnemonic, File(payerHome, "sdk"))
    }

    @AfterTest
    fun closeWallets() = runBlocking<Unit> {
        runCatching { payer.disconnect() }
        runCatching { bank.disconnect() }
        payerHome.deleteRecursively()
        Unit
    }

    @Test
    fun aSecondPaymentOfAPaidInvoiceFailsWithAlreadyExists() = runBlocking<Unit> {
        // A1: BreezPocketWallet/BreezSparkPaymentClient map "status: AlreadyExists" to
        // "already paid" (MOB-90), whether the SDK refuses while quoting or when sending.
        fund(payer, 1_000)
        val invoice = invoice(bank, 100)
        assertEquals(PaymentStatus.COMPLETED, pay(payer, invoice).status)

        val refusal = runCatching { pay(payer, invoice) }.exceptionOrNull()
            ?: fail("Paying the same invoice twice succeeded")

        assertTrue(
            "status: AlreadyExists" in refusal.message.orEmpty(),
            "Refusal was: ${refusal.message}"
        )
    }

    @Test
    fun anUnfundedWalletIsQuotedButRefusedWhenSendingWithInsufficientFunds() = runBlocking<Unit> {
        // A2: the refusal comes from sendPayment, not the quote, as a SparkException whose text
        // says "insufficient funds". paymentProblem() maps that text to a definite failure
        // (MOB-101); an unrecognised refusal after sending would mean "outcome unknown".
        val prepared = prepare(payer, invoice(bank, 100))

        val refusal = runCatching { send(payer, prepared) }.exceptionOrNull()
            ?: fail("An unfunded wallet paid")

        assertTrue(
            "insufficient funds" in refusal.message.orEmpty().lowercase(),
            "Refusal was: $refusal"
        )
    }

    @Test
    fun anExpiredInvoiceIsQuotedButRefusedWhenSendingWithItsMessage() = runBlocking<Unit> {
        // A2: as above, the text contains "has expired" (MOB-101).
        fund(payer, 1_000)
        val invoice = invoice(bank, 100, expirySecs = 1u)
        delay(3_000)
        val prepared = prepare(payer, invoice)

        val refusal = runCatching { send(payer, prepared) }.exceptionOrNull()
            ?: fail("An expired invoice was paid")

        assertTrue("has expired" in refusal.message.orEmpty(), "Refusal was: ${refusal.message}")
    }

    @Test
    fun theSameSeedFindsTheSameBalanceAfterLocalDataIsErased() = runBlocking<Unit> {
        // A4: erasing local wallet data (going back to Rayl, Spark removal) loses nothing; the
        // same phrase or passkey-derived key finds the same wallet again.
        fund(payer, 700)
        val before = balance(payer)
        payer.disconnect()
        File(payerHome, "sdk").deleteRecursively()

        payer = open(payerMnemonic, File(payerHome, "sdk"))

        assertEquals(before, balance(payer))
    }

    @Test
    fun theFeePaidIsTheQuotedLightningFee() = runBlocking<Unit> {
        // A3: Rayl quotes lightningFeeSats before confirming and reports payment.fees after. An
        // invoice without a Spark address is paid over Lightning even with preferSpark.
        fund(payer, 1_000)
        val prepared = prepare(payer, invoice(bank, 100))
        val method = assertIs<SendPaymentMethod.Bolt11Invoice>(prepared.paymentMethod)

        val payment = send(payer, prepared)

        assertEquals(PaymentStatus.COMPLETED, payment.status)
        assertEquals(method.lightningFeeSats.toLong(), payment.fees.longValue())
    }

    private suspend fun open(mnemonic: String, storage: File): BreezSdk = connect(
        ConnectRequest(
            config = defaultConfig(Network.REGTEST),
            seed = Seed.Mnemonic(mnemonic, null),
            storageDir = storage.absolutePath
        )
    )

    private suspend fun invoice(wallet: BreezSdk, sats: Long, expirySecs: UInt? = null): String =
        wallet.receivePayment(
            ReceivePaymentRequest(
                ReceivePaymentMethod.Bolt11Invoice(
                    description = "Rayl contract test",
                    amountSats = sats.toULong(),
                    expirySecs = expirySecs,
                    paymentHash = null,
                    receiverIdentityPublicKey = null
                )
            )
        ).paymentRequest

    private suspend fun prepare(wallet: BreezSdk, invoice: String): PrepareSendPaymentResponse =
        wallet.prepareSendPayment(
            PrepareSendPaymentRequest(
                paymentRequest = PaymentRequest.Input(invoice),
                amount = null,
                tokenIdentifier = null,
                conversionOptions = null,
                feePolicy = null
            )
        )

    // Rayl's debug builds prefer Spark on regtest, which has no Lightning network.
    private suspend fun send(wallet: BreezSdk, prepared: PrepareSendPaymentResponse) =
        wallet.sendPayment(
            SendPaymentRequest(
                prepared,
                SendPaymentOptions.Bolt11Invoice(preferSpark = true, completionTimeoutSecs = 60u),
                UUID.randomUUID().toString()
            )
        ).payment

    private suspend fun pay(wallet: BreezSdk, invoice: String) =
        send(wallet, prepare(wallet, invoice))

    private suspend fun fund(wallet: BreezSdk, sats: Long) {
        val start = balance(wallet)
        assertEquals(
            PaymentStatus.COMPLETED,
            pay(bank, invoice(wallet, sats)).status,
            "The regtest bank couldn't pay"
        )
        repeat(30) {
            if (balance(wallet) >= start + sats) return
            delay(1_000)
        }
        fail("Funding of $sats sats didn't arrive")
    }

    private suspend fun balance(wallet: BreezSdk): Long =
        wallet.getInfo(GetInfoRequest(ensureSynced = true)).balanceSats.toLong()

    private fun bankHome() = File(
        System.getenv("RAYL_REGTEST_HOME")
            ?: "${System.getProperty("rayl.root")}/tools/regtest/.local"
    )

    private fun bankMnemonic() = File(bankHome(), "bank-mnemonic").readText().trim()
}
