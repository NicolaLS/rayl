package xyz.lilsus.raylsuite.core.payment

import fr.acinq.bitcoin.Bech32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LnurlParserTest {
    private val parser = DefaultLnurlParser()

    @Test
    fun parsesBech32HttpsEndpoint() {
        val encoded = encode("https://pay.example.com/lnurl?tag=payRequest")

        val parsed = assertNotNull(parser.parseOrNull(encoded))

        assertEquals("https://pay.example.com/lnurl?tag=payRequest", parsed.serviceUrl)
        assertEquals(LnurlInputFormat.BECH32, parsed.inputFormat)
        assertEquals(LnurlPayStatus.KNOWN_PAY, parsed.payStatus)
    }

    @Test
    fun acceptsUppercaseBech32() {
        val encoded = encode("https://pay.example.com/lnurl").uppercase()

        assertNotNull(parser.parseOrNull(encoded))
    }

    @Test
    fun rejectsWrongHrpAndBech32m() {
        val endpoint = "https://pay.example.com/lnurl".encodeToByteArray()
        val wrongHrp = Bech32.encodeBytes("other", endpoint, Bech32.Encoding.Bech32)
        val bech32m = Bech32.encodeBytes("lnurl", endpoint, Bech32.Encoding.Bech32m)

        assertNull(parser.parseOrNull(wrongHrp))
        assertNull(parser.parseOrNull(bech32m))
    }

    @Test
    fun mapsLud17PayToTransportUrl() {
        val parsed = assertNotNull(parser.parseOrNull("lnurlp://pay.example.com/request?id=42"))

        assertEquals("https://pay.example.com/request?id=42", parsed.serviceUrl)
        assertEquals(LnurlInputFormat.LUD17_PAY, parsed.inputFormat)
        assertEquals(LnurlPayStatus.KNOWN_PAY, parsed.payStatus)
    }

    @Test
    fun rejectsGenericLnurlSchemeAndConflictingPayTag() {
        assertNull(parser.parseOrNull("lnurl://pay.example.com/request"))
        assertNull(parser.parseOrNull("lnurlp://pay.example.com/request?tag=withdrawRequest"))
    }

    @Test
    fun identifiesUnsupportedBech32Subprotocol() {
        assertEquals(
            LnurlParseResult.UnsupportedSubprotocol,
            parser.parse(encode("https://pay.example.com/request?tag=withdrawRequest"))
        )
    }

    @Test
    fun parsesLightningAddressAsPayEndpoint() {
        val parsed = assertNotNull(parser.parseOrNull("alice+tips@example.com"))

        assertEquals("https://example.com/.well-known/lnurlp/alice+tips", parsed.serviceUrl)
        assertEquals(LnurlInputFormat.LIGHTNING_ADDRESS, parsed.inputFormat)
        assertEquals(LnurlPayStatus.KNOWN_PAY, parsed.payStatus)
    }

    @Test
    fun rejectsLightningAddressesOnAnIpAddress() {
        assertNull(parser.parseOrNull("alice@127.0.0.1"))
    }

    @Test
    fun rejectsUnsafeOrUnsupportedServiceUrls() {
        assertNull(parser.parseOrNull(encode("http://pay.example.com/lnurl")))
        assertNull(parser.parseOrNull(encode("https://user@pay.example.com/lnurl")))
        assertNull(parser.parseOrNull(encode("https://pay.example.com/lnurl#fragment")))
        assertNull(parser.parseOrNull(encode("https://pay.example.com/lnurl?tag=withdrawRequest")))
    }

    private fun encode(endpoint: String): String = Bech32.encodeBytes("lnurl", endpoint.encodeToByteArray(), Bech32.Encoding.Bech32)
}
