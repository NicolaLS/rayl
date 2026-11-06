package xyz.lilsus.rayl.tools.regtest.bank

import breez_sdk_spark.BreezSdk
import breez_sdk_spark.ConnectRequest
import breez_sdk_spark.GetInfoRequest
import breez_sdk_spark.ListPaymentsRequest
import breez_sdk_spark.Network
import breez_sdk_spark.PaymentRequest
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.PaymentType
import breez_sdk_spark.PrepareSendPaymentRequest
import breez_sdk_spark.ReceivePaymentMethod
import breez_sdk_spark.ReceivePaymentRequest
import breez_sdk_spark.Seed
import breez_sdk_spark.SendPaymentOptions
import breez_sdk_spark.SendPaymentRequest
import breez_sdk_spark.connect
import breez_sdk_spark.defaultConfig
import com.ionspin.kotlin.bignum.integer.BigInteger
import fr.acinq.bitcoin.MnemonicCode
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.delay

/**
 * The counterparty of wallet integration tests: a funded Spark wallet on Lightspark's hosted
 * Regtest, the same one `tools/regtest/regtest` uses. It pays the invoices a wallet under test
 * creates and creates the invoices it pays.
 *
 * Its key lives in `$RAYL_REGTEST_HOME/bank-mnemonic` (default `tools/regtest/.local`); in CI
 * it comes from a repository secret. Top it up with `tools/regtest/regtest status`.
 */
class RegtestBank private constructor(private val sdk: BreezSdk) {
    suspend fun balanceSats(): Long = sdk.balanceSats()

    /** A BOLT11 invoice paying the bank; `null` lets the payer choose the amount. */
    suspend fun invoice(amountSats: Long?, expirySecs: UInt? = null): String =
        sdk.invoice(amountSats, expirySecs)

    /**
     * Pays [invoice] and fails unless it completes. Retries a dropped connection to Lightspark
     * with the same idempotency key, so a retry never pays twice.
     */
    suspend fun pay(invoice: String, amountSats: Long? = null) {
        val idempotencyKey = UUID.randomUUID().toString()
        val payment = retryingDroppedConnections {
            val prepared = sdk.prepareSendPayment(
                PrepareSendPaymentRequest(
                    paymentRequest = PaymentRequest.Input(invoice),
                    amount = amountSats?.let(BigInteger::fromLong),
                    tokenIdentifier = null,
                    conversionOptions = null,
                    feePolicy = null
                )
            )
            sdk.sendPayment(
                SendPaymentRequest(
                    prepared,
                    // Regtest has no Lightning network: invoices of Spark wallets settle over Spark.
                    SendPaymentOptions.Bolt11Invoice(
                        preferSpark = true,
                        completionTimeoutSecs = 60u
                    ),
                    idempotencyKey
                )
            ).payment
        }
        check(payment.status == PaymentStatus.COMPLETED) {
            "The regtest bank's payment ended ${payment.status}; check its balance"
        }
    }

    /** Sends [sats] to [wallet] and waits until its balance shows them. */
    suspend fun fund(wallet: BreezSdk, sats: Long) {
        val start = wallet.balanceSats()
        pay(wallet.invoice(sats, expirySecs = null))
        awaitBalance(wallet) { it >= start + sats }
    }

    /**
     * Sends everything a test wallet holds back to the bank, as a Spark transfer (no fee), so a
     * test run costs only routing fees. Best effort: a test's result never depends on it.
     */
    suspend fun reclaim(mnemonic: String, storage: File) {
        runCatching {
            val wallet = connectWallet(mnemonic, storage)
            try {
                val balance = wallet.balanceSats()
                if (balance <= 0) return@runCatching
                val prepared = wallet.prepareSendPayment(
                    PrepareSendPaymentRequest(
                        paymentRequest = PaymentRequest.Input(sparkAddress()),
                        amount = BigInteger.fromLong(balance),
                        tokenIdentifier = null,
                        conversionOptions = null,
                        feePolicy = null
                    )
                )
                wallet.sendPayment(SendPaymentRequest(prepared, null, UUID.randomUUID().toString()))
            } finally {
                wallet.disconnect()
            }
        }
    }

    suspend fun close() = sdk.disconnect()

    private suspend fun sparkAddress(): String =
        sdk.receivePayment(ReceivePaymentRequest(ReceivePaymentMethod.SparkAddress)).paymentRequest

    companion object {
        /** Opens the bank. Integration tests run one at a time, so its storage is never shared. */
        suspend fun open(): RegtestBank {
            val home = File(
                System.getenv("RAYL_REGTEST_HOME")
                    ?: "${System.getProperty("rayl.root") ?: "."}/tools/regtest/.local"
            )
            val mnemonic = File(home, "bank-mnemonic")
            check(mnemonic.isFile) {
                "No regtest bank in $home: run tools/regtest/regtest status, or set RAYL_REGTEST_HOME"
            }
            return RegtestBank(
                retryingDroppedConnections {
                    connectWallet(mnemonic.readText().trim(), File(home, "sdk"))
                }
            )
        }

        /** A new, empty wallet's recovery phrase. */
        fun newMnemonic(): String = MnemonicCode.toMnemonics(
            ByteArray(16).also(SecureRandom()::nextBytes)
        ).joinToString(" ")

        /** A raw regtest SDK session, for tests that drive Rayl's code with their own wallet. */
        suspend fun connectWallet(mnemonic: String, storage: File): BreezSdk = connect(
            ConnectRequest(
                config = defaultConfig(Network.REGTEST),
                seed = Seed.Mnemonic(mnemonic, null),
                storageDir = storage.absolutePath
            )
        )

        /** Waits until [wallet]'s balance meets [condition], then returns it. */
        suspend fun awaitBalance(wallet: BreezSdk, condition: (Long) -> Boolean): Long {
            var balance = wallet.balanceSats()
            repeat(BALANCE_POLLS) {
                if (condition(balance)) return balance
                delay(1_000)
                balance = wallet.balanceSats()
            }
            error("The wallet's balance stayed at $balance sats")
        }

        /**
         * Checks that [wallet]'s balance reaches exactly [expected] sats. On failure the message
         * lists the payments Breez holds for it, so a pending send or missing piece shows up.
         */
        suspend fun assertBalance(wallet: BreezSdk, expected: Long) {
            val reached = runCatching { awaitBalance(wallet) { it == expected } }
            if (reached.isSuccess) return
            val payments = wallet.listPayments(ListPaymentsRequest()).payments.joinToString("; ") {
                "${it.paymentType} ${it.status} ${it.amount}+${it.fees} ${it.method}"
            }
            throw AssertionError(
                "Balance stayed at ${wallet.balanceSats()} sats, expected $expected. Payments: $payments"
            )
        }

        /**
         * Checks that Breez's payment history for [wallet] reaches exactly [expected], in any
         * order. The history says precisely what was spent, so it is the main oracle; the balance
         * can lag behind it (MOB-114).
         */
        suspend fun assertPayments(wallet: BreezSdk, vararg expected: PaymentRecord) {
            var actual = wallet.payments()
            repeat(BALANCE_POLLS) {
                if (actual.sorted() == expected.sorted()) return
                delay(1_000)
                actual = wallet.payments()
            }
            throw AssertionError("Payments were $actual, expected ${expected.toList()}")
        }

        private suspend fun BreezSdk.payments(): List<PaymentRecord> {
            getInfo(GetInfoRequest(ensureSynced = true))
            return listPayments(ListPaymentsRequest()).payments.map {
                PaymentRecord(
                    if (it.paymentType == PaymentType.SEND) Direction.Sent else Direction.Received,
                    it.status,
                    it.amount.longValue(),
                    it.fees.longValue()
                )
            }
        }

        private fun Array<out PaymentRecord>.sorted() = toList().sorted()

        private fun List<PaymentRecord>.sorted() = sortedBy { it.toString() }

        private const val BALANCE_POLLS = 60
    }
}

/** One payment as Breez records it. */
data class PaymentRecord(
    val direction: Direction,
    val status: PaymentStatus,
    val amountSats: Long,
    val feeSats: Long
) {
    companion object {
        fun sent(amountSats: Long, feeSats: Long) =
            PaymentRecord(Direction.Sent, PaymentStatus.COMPLETED, amountSats, feeSats)

        fun received(amountSats: Long) =
            PaymentRecord(Direction.Received, PaymentStatus.COMPLETED, amountSats, 0)
    }
}

enum class Direction { Sent, Received }

private suspend fun BreezSdk.balanceSats(): Long =
    getInfo(GetInfoRequest(ensureSynced = true)).balanceSats.toLong()

private suspend fun BreezSdk.invoice(amountSats: Long?, expirySecs: UInt?): String = receivePayment(
    ReceivePaymentRequest(
        ReceivePaymentMethod.Bolt11Invoice(
            description = "Rayl integration test",
            amountSats = amountSats?.toULong(),
            expirySecs = expirySecs,
            paymentHash = null,
            receiverIdentityPublicKey = null
        )
    )
).paymentRequest

/**
 * Lightspark's hosted Regtest sometimes drops connections ("network error … peer closed
 * connection"). The bank is test infrastructure, so it retries them; the wallet under test
 * never does, so its own classification of those errors stays visible.
 */
private suspend fun <T> retryingDroppedConnections(block: suspend () -> T): T {
    repeat(RETRIES) { attempt ->
        try {
            return block()
        } catch (error: Exception) {
            if ("network error" !in error.message.orEmpty() || attempt == RETRIES - 1) throw error
            delay(2_000L * (attempt + 1))
        }
    }
    error("unreachable")
}

private const val RETRIES = 4
