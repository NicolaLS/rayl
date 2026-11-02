package xyz.lilsus.raylsuite.core.payment

import fr.acinq.bitcoin.ByteVector32
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.model.LightningAddress

class LnurlInvoiceResolverTest {
    @Test
    fun resolvesAndRoundsAValidInvoice() = runTest {
        val client = FakeLnurlPayClient { amount, _ ->
            LnurlResult.Success(invoice(amountMsats = amount).write())
        }

        val result = resolver(client).resolve(request(amountMsats = 100_001L))

        val success = assertIs<LnurlInvoiceResolution.Success>(result)
        assertEquals(101_000L, success.amountMsats)
        assertEquals(101_000L, success.invoice.amount?.msat)
    }

    @Test
    fun rejectsUnsupportedCommentBeforeRequestingInvoice() = runTest {
        val client = FakeLnurlPayClient { _, _ -> error("invoice must not be requested") }

        val result =
            resolver(client).resolve(
                request(comment = "too long", params = params(commentAllowed = 3))
            )

        assertIs<LnurlInvoiceResolutionError.CommentRejected>(failure(result))
        assertEquals(0, client.requestCount)
    }

    @Test
    fun rejectsMalformedInvoice() = runTest {
        val client = FakeLnurlPayClient { _, _ -> LnurlResult.Success("not-an-invoice") }

        val result = resolver(client).resolve(request())

        assertIs<LnurlInvoiceResolutionError.MalformedInvoice>(failure(result))
    }

    @Test
    fun rejectsExpiredInvoice() = runTest {
        val client = FakeLnurlPayClient { amount, _ ->
            LnurlResult.Success(invoice(amountMsats = amount, timestampSeconds = 1L).write())
        }

        val result = LnurlInvoiceResolver(client) { 10_000L }.resolve(request())

        assertIs<LnurlInvoiceResolutionError.ExpiredInvoice>(failure(result))
    }

    @Test
    fun rejectsAmountMismatch() = runTest {
        val client = FakeLnurlPayClient { _, _ ->
            LnurlResult.Success(invoice(amountMsats = 200_000L).write())
        }

        val result = resolver(client).resolve(request(amountMsats = 100_000L))

        val error = assertIs<LnurlInvoiceResolutionError.AmountMismatch>(failure(result))
        assertEquals(100_000L, error.expectedMsats)
        assertEquals(200_000L, error.actualMsats)
    }

    @Test
    fun rejectsMetadataMismatch() = runTest {
        val client = FakeLnurlPayClient { amount, _ ->
            LnurlResult.Success(invoice(amountMsats = amount, description = "different").write())
        }

        val result = resolver(client).resolve(request())

        assertIs<LnurlInvoiceResolutionError.MetadataMismatch>(failure(result))
    }

    @Test
    fun rejectsUnrepresentableAndOutOfRangeAmountsBeforeRequesting() = runTest {
        val client = FakeLnurlPayClient { _, _ -> error("invoice must not be requested") }
        val resolver = resolver(client)
        listOf(0L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 999L, 1_000_001L).forEach { amount ->
            assertIs<LnurlInvoiceResolutionError.AmountOutOfRange>(failure(resolver.resolve(request(amountMsats = amount))))
        }
        assertIs<LnurlInvoiceResolutionError.AmountOutOfRange>(
            failure(
                resolver.resolve(
                    request(amountMsats = 1_500L, params = params().copy(minSendable = 1_500L, maxSendable = 1_500L))
                )
            )
        )
        assertEquals(0, client.requestCount)
        assertEquals(1_000L, roundToFullSatoshis(1L))
        assertEquals(Long.MAX_VALUE - 807L, roundToFullSatoshis(Long.MAX_VALUE - 807L))
        assertNull(roundToFullSatoshis(Long.MAX_VALUE - 806L))
    }

    @Test
    fun theServersExactLimitsAreAccepted() = runTest {
        // The minimum, the maximum and a comment of exactly the allowed length are all valid.
        val client = FakeLnurlPayClient { amount, _ -> LnurlResult.Success(invoice(amount).write()) }
        val resolver = resolver(client)

        listOf(1_000L, 1_000_000L).forEach { amount ->
            assertIs<LnurlInvoiceResolution.Success>(resolver.resolve(request(amountMsats = amount)), "amount $amount")
        }
        assertIs<LnurlInvoiceResolution.Success>(
            resolver.resolve(request(comment = "abc", params = params(commentAllowed = 3)))
        )
    }

    @Test
    fun aFailedInvoiceRequestIsReportedAndPaysNothing() = runTest {
        val client = FakeLnurlPayClient { _, _ -> LnurlResult.Error(LnurlError.NetworkUnavailable) }

        val result = resolver(client).resolve(request())

        assertEquals(LnurlInvoiceResolutionError.Client(LnurlError.NetworkUnavailable), failure(result))
    }

    @Test
    fun rejectsAbsentPlaintextInsteadOfAcceptingAnArbitraryInvoiceDescription() = runTest {
        val client = FakeLnurlPayClient { amount, _ -> LnurlResult.Success(invoice(amount).write()) }
        val params = params().let { it.copy(metadata = it.metadata.copy(plainText = null)) }
        val result = resolver(client).resolve(request(params = params))
        assertIs<LnurlInvoiceResolutionError.MetadataMismatch>(failure(result))
        assertEquals(0, client.requestCount)
    }

    @Test
    fun anInvoiceBoundByTheMetadataHashIsAccepted() = runTest {
        // LUD-06 binds the invoice to the metadata with description_hash, which is how most
        // LNURL servers in production do it.
        val client = FakeLnurlPayClient { amount, _ ->
            LnurlResult.Success(invoice(amount, descriptionHash = sha256(METADATA_RAW)).write())
        }

        assertIs<LnurlInvoiceResolution.Success>(resolver(client).resolve(request()))
    }

    @Test
    fun anInvoiceBoundToOtherMetadataIsRejected() = runTest {
        // A server could return an invoice made for another payment description (PR-05).
        val client = FakeLnurlPayClient { amount, _ ->
            LnurlResult.Success(invoice(amount, descriptionHash = sha256("[[\"text/plain\",\"other\"]]")).write())
        }

        assertIs<LnurlInvoiceResolutionError.MetadataMismatch>(failure(resolver(client).resolve(request())))
    }

    @Test
    fun anAmountlessInvoiceFromTheServerIsRejected() = runTest {
        // The server must return an invoice for the requested amount; an amountless one would
        // leave the amount paid open.
        val client = FakeLnurlPayClient { _, _ -> LnurlResult.Success(invoice(amountMsats = null).write()) }

        val error = assertIs<LnurlInvoiceResolutionError.AmountMismatch>(failure(resolver(client).resolve(request())))
        assertNull(error.actualMsats)
    }

    private fun sha256(text: String) = Crypto.sha256(text.encodeToByteArray()).toByteVector32()

    private fun resolver(client: LnurlPayClient) = LnurlInvoiceResolver(client) { 1L }

    private fun request(amountMsats: Long = 100_000L, comment: String? = null, params: LnurlPayParams = params()) =
        LnurlInvoiceRequest(params, amountMsats, comment)

    private fun params(commentAllowed: Int? = 20) = LnurlPayParams(
        callback = "https://pay.example/callback",
        minSendable = 1_000L,
        maxSendable = 1_000_000L,
        metadataRaw = METADATA_RAW,
        metadata =
            LnurlPayMetadata(
                plainText = "coffee",
                longText = null,
                imagePng = null,
                imageJpeg = null,
                identifier = null,
                email = null,
                tag = null
            ),
        commentAllowed = commentAllowed,
        domain = "pay.example"
    )

    private fun failure(result: LnurlInvoiceResolution): LnurlInvoiceResolutionError =
        assertIs<LnurlInvoiceResolution.Failure>(result).error

    private fun invoice(
        amountMsats: Long?,
        description: String = "coffee",
        timestampSeconds: Long = 1L,
        descriptionHash: ByteVector32? = null
    ): Bolt11Invoice = Bolt11Invoice.create(
        chain = Chain.Mainnet,
        amount = amountMsats?.msat,
        paymentHash = Crypto.sha256("payment-$amountMsats-$description".encodeToByteArray())
            .toByteVector32(),
        privateKey = PrivateKey(ByteArray(32) { 1 }),
        description = descriptionHash?.let { Either.Right(it) } ?: Either.Left(description),
        minFinalCltvExpiryDelta = Bolt11Invoice.DEFAULT_MIN_FINAL_EXPIRY_DELTA,
        features =
            Features(
                Feature.VariableLengthOnion to FeatureSupport.Optional,
                Feature.PaymentSecret to FeatureSupport.Optional
            ),
        timestampSeconds = timestampSeconds
    )
}

private const val METADATA_RAW = "[[\"text/plain\",\"coffee\"]]"

private class FakeLnurlPayClient(private val response: (amountMsats: Long, comment: String?) -> LnurlResult<String>) : LnurlPayClient {
    var requestCount = 0
        private set

    override suspend fun fetchPayParams(endpoint: String): LnurlResult<LnurlPayParams> = error("not used")

    override suspend fun fetchPayParams(address: LightningAddress): LnurlResult<LnurlPayParams> = error("not used")

    override suspend fun requestInvoice(callback: String, amountMsats: Long, comment: String?): LnurlResult<String> {
        requestCount += 1
        return response(amountMsats, comment)
    }
}
