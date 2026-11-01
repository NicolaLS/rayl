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
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LightningInputParserTest {
    private val parser = LightningInputParser()

    @Test
    fun rawAndLightningPrefixedBolt11ArePayable() = runParser {
        val encoded = invoice().write()

        assertIs<LightningInputParser.Target.Bolt11>(successTarget(parser.parse(encoded)))
        assertIs<LightningInputParser.Target.Bolt11>(
            successTarget(parser.parse("lightning:$encoded"))
        )
        Unit
    }

    @Test
    fun lud17PayIsAnLnurlTarget() = runParser {
        val target = successTarget(parser.parse("lnurlp://pay.example.com/request"))

        assertEquals(
            LnurlInputFormat.LUD17_PAY,
            assertIs<LightningInputParser.Target.Lnurl>(target).request.inputFormat
        )
    }

    @Test
    fun bitcoinLightningFallbackRestartsParsing() = runParser {
        val encoded = invoice().write()

        assertIs<LightningInputParser.Target.Bolt11>(
            successTarget(parser.parse("bitcoin:bc1qexample?lightning=$encoded"))
        )
        Unit
    }

    @Test
    fun lud01UriFallbackRestartsParsingWithoutFetchingOuterUri() = runParser {
        val lnurl = encodeLnurl("https://pay.example.com/request")

        assertIs<LightningInputParser.Target.Lnurl>(
            successTarget(parser.parse("https://merchant.example/checkout?lightning=$lnurl"))
        )
        Unit
    }

    @Test
    fun knownUnsupportedFormatsAreReportedAfterPayableParsers() = runParser {
        assertEquals(
            LightningInputParser.FailureReason.UnsupportedLnurl,
            assertIs<LightningInputParser.ParseResult.Failure>(
                parser.parse("lnurlw://wallet.example.com/request")
            ).reason
        )
        assertEquals(
            LightningInputParser.FailureReason.BitcoinAddress,
            assertIs<LightningInputParser.ParseResult.Failure>(
                parser.parse("bitcoin:bc1qexample")
            ).reason
        )
    }

    private fun successTarget(result: LightningInputParser.ParseResult): LightningInputParser.Target =
        assertIs<LightningInputParser.ParseResult.Success>(result).target

    private fun encodeLnurl(endpoint: String): String = Bech32.encodeBytes("lnurl", endpoint.encodeToByteArray(), Bech32.Encoding.Bech32)

    private fun invoice(): Bolt11Invoice = Bolt11Invoice.create(
        chain = Chain.Mainnet,
        amount = 100_000L.msat,
        paymentHash = Crypto.sha256("payment".encodeToByteArray()).toByteVector32(),
        privateKey = PrivateKey(ByteArray(32) { 1 }),
        description = Either.Left("test"),
        minFinalCltvExpiryDelta = Bolt11Invoice.DEFAULT_MIN_FINAL_EXPIRY_DELTA,
        features =
            Features(
                Feature.VariableLengthOnion to FeatureSupport.Optional,
                Feature.PaymentSecret to FeatureSupport.Optional
            ),
        timestampSeconds = 1L
    )
}

private fun <T> runParser(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        }
    )
    return checkNotNull(outcome).getOrThrow()
}
