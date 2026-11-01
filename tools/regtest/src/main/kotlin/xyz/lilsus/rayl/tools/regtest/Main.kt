package xyz.lilsus.rayl.tools.regtest

import breez_sdk_spark.BreezSdk
import breez_sdk_spark.ClaimDepositRequest
import breez_sdk_spark.ConnectRequest
import breez_sdk_spark.EventListener
import breez_sdk_spark.GetInfoRequest
import breez_sdk_spark.ListUnclaimedDepositsRequest
import breez_sdk_spark.MaxFee
import breez_sdk_spark.MaxOnchainFee
import breez_sdk_spark.Network
import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.PaymentRequest
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.PaymentType
import breez_sdk_spark.PrepareSendPaymentRequest
import breez_sdk_spark.ReceivePaymentMethod
import breez_sdk_spark.ReceivePaymentRequest
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.Seed
import breez_sdk_spark.SendPaymentMethod
import breez_sdk_spark.SendPaymentOptions
import breez_sdk_spark.SendPaymentRequest
import breez_sdk_spark.connect
import breez_sdk_spark.defaultConfig
import com.ionspin.kotlin.bignum.integer.BigInteger
import fr.acinq.bitcoin.MnemonicCode
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import kotlin.system.exitProcess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val USAGE = """Rayl regtest bank: a Spark wallet on Lightspark's hosted Regtest.

Usage: regtest <command>

  status                         Balance, funding addresses; claims confirmed deposits
  pay <invoice> [--amount SATS]  Pay a Rayl invoice (e.g. from Add funds)
  pay --from android             Pay the latest Add funds invoice a connected debug
                                 build logged (set ANDROID_SERIAL for several devices)
  invoice <SATS> [--no-wait]     Create an invoice for Rayl to pay, with a QR to scan

State lives in tools/regtest/.local (override with RAYL_REGTEST_HOME). Regtest only."""

fun main(args: Array<String>) {
    val command = args.firstOrNull()
    if (command == null || command in setOf("-h", "--help", "help")) {
        println(USAGE)
        return
    }
    val options = Options(args.drop(1))
    // Resolve the invoice before connecting, so a missing one fails fast.
    val payInvoice = if (command == "pay") {
        when (options.value("--from")) {
            null -> options.positional(0, "invoice")
            "android" -> latestAndroidInvoice()
            else -> fail("--from must be 'android'")
        }
    } else {
        null
    }
    val code = runBlocking {
        val bank = Bank.open()
        try {
            when (command) {
                "status" -> bank.status()

                "pay" -> bank.pay(checkNotNull(payInvoice), options.long("--amount"))

                "invoice" -> bank.invoice(
                    amountSats = options.positional(0, "amount in sats").toLongOrNull()
                        ?.takeIf { it > 0 } ?: fail("Amount must be a positive number of sats"),
                    wait = !options.flag("--no-wait")
                )

                else -> fail("Unknown command '$command'.\n\n$USAGE")
            }
        } finally {
            bank.close()
        }
    }
    exitProcess(code)
}

private class Bank(private val sdk: BreezSdk) {
    private val events = mutableListOf<(SdkEvent) -> Unit>()

    suspend fun status(): Int {
        val info = sdk.getInfo(GetInfoRequest(ensureSynced = true))
        claimDeposits()
        val spark = receive(ReceivePaymentMethod.SparkAddress)
        val bitcoin = receive(ReceivePaymentMethod.BitcoinAddress(newAddress = false))
        println("Balance:         ${info.balanceSats} sats")
        println("Spark address:   $spark")
        println("Deposit address: $bitcoin")
        println("Faucet:          https://app.lightspark.com/regtest-faucet")
        return 0
    }

    suspend fun pay(invoice: String, amountSats: Long?): Int {
        val prepared = sdk.prepareSendPayment(
            PrepareSendPaymentRequest(
                paymentRequest = PaymentRequest.Input(invoice.removePrefix("lightning:")),
                amount = amountSats?.let(BigInteger::fromLong),
                tokenIdentifier = null,
                conversionOptions = null,
                feePolicy = null
            )
        )
        val options = when (prepared.paymentMethod) {
            // Regtest has no Lightning network: invoices from Spark wallets settle over Spark.
            is SendPaymentMethod.Bolt11Invoice -> SendPaymentOptions.Bolt11Invoice(
                preferSpark = true,
                completionTimeoutSecs = 60u
            )

            else -> null
        }
        val payment = sdk.sendPayment(
            SendPaymentRequest(prepared, options, UUID.randomUUID().toString())
        ).payment
        println("Payment ${payment.status.name.lowercase()}: ${payment.amount} sats")
        return if (payment.status == PaymentStatus.FAILED) 1 else 0
    }

    suspend fun invoice(amountSats: Long, wait: Boolean): Int {
        val received = CompletableDeferred<Unit>()
        var invoice = ""
        events += { event ->
            val payment = (event as? SdkEvent.PaymentSucceeded)?.payment
            val details = payment?.details as? PaymentDetails.Lightning
            if (payment?.paymentType == PaymentType.RECEIVE && details?.invoice == invoice) {
                received.complete(Unit)
            }
        }
        invoice = receive(
            ReceivePaymentMethod.Bolt11Invoice(
                description = "Rayl regtest",
                amountSats = amountSats.toULong(),
                expirySecs = null,
                paymentHash = null,
                receiverIdentityPublicKey = null
            )
        )
        // The QR goes to stderr so stdout stays just the invoice, e.g. for $(regtest invoice …).
        // Uppercase invoices encode in the denser alphanumeric mode.
        System.err.print(terminalQr(invoice.uppercase()))
        println(invoice)
        if (!wait) return 0
        System.err.println("Waiting for payment (up to 10 minutes)…")
        return if (withTimeoutOrNull(600_000) { received.await() } != null) {
            System.err.println("Paid.")
            0
        } else {
            System.err.println("Not paid.")
            1
        }
    }

    suspend fun close() = sdk.disconnect()

    private suspend fun receive(method: ReceivePaymentMethod): String =
        sdk.receivePayment(ReceivePaymentRequest(method)).paymentRequest

    private suspend fun claimDeposits() {
        val deposits = sdk.listUnclaimedDeposits(ListUnclaimedDepositsRequest).deposits
        deposits.forEach { deposit ->
            // Regtest coins are worthless, so accept whatever claim fee the network needs.
            runCatching {
                sdk.claimDeposit(
                    ClaimDepositRequest(
                        deposit.txid,
                        deposit.vout,
                        MaxFee(onchain = MaxOnchainFee.Fixed(10_000u))
                    )
                )
            }.onSuccess {
                println("Claimed deposit: ${deposit.amountSats} sats")
            }.onFailure {
                println("Deposit ${deposit.txid}:${deposit.vout} not claimable yet: ${it.message}")
            }
        }
    }

    private suspend fun listen() {
        sdk.addEventListener(
            object : EventListener {
                override suspend fun onEvent(event: SdkEvent) {
                    events.toList().forEach { it(event) }
                }
            }
        )
    }

    companion object {
        suspend fun open(): Bank {
            val home = File(
                System.getenv("RAYL_REGTEST_HOME") ?: File("tools/regtest/.local").absolutePath
            ).also { it.mkdirs() }
            val mnemonicFile = File(home, "bank-mnemonic")
            if (!mnemonicFile.exists()) {
                val entropy = ByteArray(16).also(SecureRandom()::nextBytes)
                mnemonicFile.writeText(MnemonicCode.toMnemonics(entropy).joinToString(" "))
                System.err.println("Created a new regtest bank wallet in $home")
            }
            val sdk = connect(
                ConnectRequest(
                    config = defaultConfig(Network.REGTEST),
                    seed = Seed.Mnemonic(mnemonicFile.readText().trim(), null),
                    storageDir = File(home, "sdk").absolutePath
                )
            )
            return Bank(sdk).also { it.listen() }
        }
    }
}

private class Options(private val args: List<String>) {
    fun flag(name: String) = name in args

    fun value(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let {
        args.getOrNull(it + 1)
            ?: fail("$name needs a value")
    }

    fun long(name: String): Long? =
        value(name)?.let { it.toLongOrNull() ?: fail("$name must be a number") }

    fun positional(index: Int, name: String): String = args.filterIndexed { i, arg ->
        !arg.startsWith("--") && args.getOrNull(i - 1)?.let { it in VALUED } != true
    }.getOrNull(index) ?: fail("Missing $name.\n\n$USAGE")

    private companion object {
        val VALUED = setOf("--amount", "--from")
    }
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(2)
}

/** The latest invoice a debug Rayl build logged under the `RaylRegtest` tag on the device. */
private fun latestAndroidInvoice(): String {
    val process = ProcessBuilder("adb", "logcat", "-d", "-v", "raw", "-s", "RaylRegtest:I")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor() != 0) fail("adb logcat failed:\n$output")
    return output.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("invoice ") }
        .lastOrNull()
        ?.removePrefix("invoice ")
        ?.also { System.err.println("Paying the latest Android invoice: ${it.take(24)}…") }
        ?: fail("No Rayl invoice in the device log. Create one in Add funds (debug build).")
}
