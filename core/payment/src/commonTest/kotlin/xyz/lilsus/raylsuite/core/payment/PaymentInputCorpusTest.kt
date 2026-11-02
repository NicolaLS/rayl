package xyz.lilsus.raylsuite.core.payment

import fr.acinq.bitcoin.Bech32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.toByteVector32
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.payment.LightningInputParser.FailureReason
import xyz.lilsus.raylsuite.core.payment.LightningInputParser.ParseResult
import xyz.lilsus.raylsuite.core.payment.LightningInputParser.Target

/**
 * What Rayl does with the strings people actually scan or paste (risk PR-14).
 *
 * Add a row whenever a real-world payment string fails or looks unusual (merchants, PoS
 * terminals, LNURL services, other wallets' QR codes), together with what it should become.
 */
class PaymentInputCorpusTest {
    private val parser = LightningInputParser()
    private val bolt11 = invoice().write()
    private val lnurl = Bech32.encodeBytes(
        "lnurl",
        "https://pay.example.com/lnurlp/alice".encodeToByteArray(),
        Bech32.Encoding.Bech32
    )

    private enum class Expected { Bolt11, Lnurl, LightningAddress }

    @Test
    fun everyCorpusStringIsClassifiedAsExpected() = runTest {
        val corpus: List<Pair<String, Any>> = listOf(
            // BOLT11. QR codes often use uppercase, because alphanumeric mode is denser.
            bolt11 to Expected.Bolt11,
            bolt11.uppercase() to Expected.Bolt11,
            "lightning:$bolt11" to Expected.Bolt11,
            "LIGHTNING:${bolt11.uppercase()}" to Expected.Bolt11,
            "  lightning:$bolt11\n" to Expected.Bolt11,
            // BIP21 with a Lightning fallback.
            "bitcoin:bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4?amount=0.001&lightning=$bolt11" to Expected.Bolt11,
            "BITCOIN:BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4?LIGHTNING=${bolt11.uppercase()}" to Expected.Bolt11,
            // LNURL-pay: bech32 (LUD-01) in both cases, with the scheme, and LUD-17.
            lnurl to Expected.Lnurl,
            lnurl.uppercase() to Expected.Lnurl,
            "lightning:${lnurl.uppercase()}" to Expected.Lnurl,
            "lnurlp://pay.example.com/lnurlp/alice" to Expected.Lnurl,
            // LUD-01 fallback: a merchant web link carrying the LNURL in a lightning parameter.
            "https://merchant.example/checkout?lightning=$lnurl" to Expected.Lnurl,
            // Lightning addresses become address targets, so the Hub can offer to save them.
            "alice@example.com" to Expected.LightningAddress,
            "alice+tips@example.com" to Expected.LightningAddress,
            " alice@example.com " to Expected.LightningAddress,
            // Phone keyboards capitalise the first letter (MOB-89).
            "Alice@example.com" to Expected.LightningAddress,
            // Rejected with a reason the user can act on.
            "" to FailureReason.Empty,
            "   " to FailureReason.Empty,
            "bitcoin:bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4" to FailureReason.BitcoinAddress,
            // A malformed escape in the Lightning fallback must not crash the scanner. The fallback
            // follows the undecodable value, so it ends as Unrecognized rather than BitcoinAddress.
            "bitcoin:bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4?lightning=%zz" to FailureReason.Unrecognized,
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4" to FailureReason.BitcoinAddress,
            "lnurlw://wallet.example.com/withdraw" to FailureReason.UnsupportedLnurl,
            "hello" to FailureReason.Unrecognized
        )

        corpus.forEach { (input, expected) ->
            val result = parser.parse(input)
            val actual: Any = when (result) {
                is ParseResult.Failure -> result.reason

                is ParseResult.Success -> when (result.target) {
                    is Target.Bolt11 -> Expected.Bolt11
                    is Target.Lnurl -> Expected.Lnurl
                    is Target.LightningAddressTarget -> Expected.LightningAddress
                    else -> result.target
                }
            }
            assertEquals(expected, actual, "input: \"$input\"")
        }
    }

    @Test
    fun aCapitalisedLightningAddressIsPaidInLowercase() = runTest {
        // LUD-16 user names are lowercase, and the LNURL endpoint is looked up by that name
        // (MOB-89).
        val result = parser.parse("Alice+Tips@Example.COM")

        val target = (result as? ParseResult.Success)?.target as? Target.LightningAddressTarget
        assertEquals("alice+tips@example.com", target?.address?.full, "$result")
    }

    @Test
    fun aDamagedInvoiceIsReportedAsInvalidNotUnrecognized() = runTest {
        // One wrong character fails the checksum. "Invalid invoice" tells the user to rescan.
        val damaged = bolt11.dropLast(1) + if (bolt11.last() == 'q') 'p' else 'q'

        val result = parser.parse(damaged)

        assertEquals(true, (result as? ParseResult.Failure)?.reason is FailureReason.InvalidInvoice, "$result")
    }

    @Test
    fun theParserNeverThrowsOnArbitraryInput() = runTest {
        // A scanned QR code can contain anything. A crash here takes down the scanner. Inputs mix
        // payment-shaped fragments with arbitrary text; a failure is shrunk to a minimal input.
        val fragments = Arb.element(
            "lightning:", "LIGHTNING:", "bitcoin:", "lnbc", "lntb", "lnbcrt", "lno1", "lnurl", "LNURL",
            "lnurlp://", "lnurlw://", "keyauth://", "https://", "@", "?", "&", "=", "%", "%zz", "+",
            "lightning=", ":", "//", "#", " ", "\n", "\u202E", "é", bolt11.take(20), lnurl.take(12)
        )
        val inputs = Arb.list(Arb.choice(fragments, Arb.string(1..8)), 0..12)
            .map { parts -> parts.joinToString("") }

        checkAll(PropTestConfig(seed = 20261101, iterations = 3_000), inputs) { input ->
            parser.parse(input)
        }
    }

    private fun invoice(): Bolt11Invoice = Bolt11Invoice.create(
        chain = Chain.Mainnet,
        amount = 21_000L.msat,
        paymentHash = Crypto.sha256("corpus".encodeToByteArray()).toByteVector32(),
        privateKey = PrivateKey(ByteArray(32) { 3 }),
        description = Either.Left("coffee"),
        minFinalCltvExpiryDelta = Bolt11Invoice.DEFAULT_MIN_FINAL_EXPIRY_DELTA,
        features =
            Features(
                Feature.VariableLengthOnion to FeatureSupport.Optional,
                Feature.PaymentSecret to FeatureSupport.Optional
            ),
        timestampSeconds = 1L
    )
}
