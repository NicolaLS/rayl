package xyz.lilsus.raylsuite.core.payment

import com.eygraber.uri.Uri
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.utils.Try
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.payment.PaymentRequest
import fr.acinq.lightning.wire.OfferTypes
import xyz.lilsus.raylsuite.core.model.LightningAddress

/**
 * Suite-level composition of provider-neutral payment-input parsers.
 *
 * [additionalInstantParsers] are placed immediately after raw BOLT11, so a provider such as Spark
 * can add its native Spark parser without changing shared priorities. List order is the only
 * priority mechanism; there is no mutable or numeric parser registry.
 */
class LightningInputParser(
    additionalInstantParsers: List<PaymentInputParser<Target, FailureReason>> = emptyList(),
    private val lnurlParser: LnurlParser = DefaultLnurlParser()
) {
    private val parser =
        OrderedPaymentInputParser(
            supportedParsers =
                buildList {
                    add(PaymentInputParser(::parseBolt11))
                    addAll(additionalInstantParsers)
                    add(PaymentInputParser(::unwrapLightningScheme))
                    add(PaymentInputParser(::parseLnurl))
                    add(PaymentInputParser(::unwrapLightningFallback))
                },
            unsupportedParsers =
                listOf(
                    PaymentInputParser(::recognizeUnsupportedLnurl),
                    PaymentInputParser(::recognizeBolt12),
                    PaymentInputParser(::recognizeBitcoin)
                )
        )

    suspend fun parse(raw: String): ParseResult {
        val input = raw.trim()
        if (input.isEmpty()) return ParseResult.Failure(FailureReason.Empty)

        return parser.parse(input).toParseResult()
    }

    sealed interface ParseResult {
        data class Success(val target: Target) : ParseResult

        data class Failure(val reason: FailureReason) : ParseResult
    }

    sealed interface FailureReason {
        data object Empty : FailureReason

        data object BitcoinAddress : FailureReason

        data object Bolt12 : FailureReason

        data object UnsupportedLnurl : FailureReason

        data object InvalidLnurl : FailureReason

        data class InvalidInvoice(val reason: String?) : FailureReason

        data object Unrecognized : FailureReason
    }

    interface Target {
        data class Lnurl(val request: ParsedLnurl) : Target {
            val endpoint: String
                get() = request.serviceUrl
        }

        data class LightningAddressTarget(val address: LightningAddress) : Target

        data class Bolt11(val invoice: Bolt11Invoice) : Target
    }

    private fun parseBolt11(value: String): PaymentInputParseAttempt<Target, FailureReason> {
        if (!looksLikeBolt11(value)) return PaymentInputParseAttempt.NoMatch

        return when (val decoded = PaymentRequest.read(value)) {
            is Try.Success -> {
                val invoice = decoded.result as? Bolt11Invoice
                    ?: return PaymentInputParseAttempt.Rejected(FailureReason.Bolt12)
                PaymentInputParseAttempt.Parsed(Target.Bolt11(invoice))
            }

            is Try.Failure ->
                PaymentInputParseAttempt.Rejected(
                    FailureReason.InvalidInvoice(decoded.error.message)
                )
        }
    }

    private fun unwrapLightningScheme(
        value: String
    ): PaymentInputParseAttempt<Target, FailureReason> {
        if (!value.startsWith(LIGHTNING_PREFIX, ignoreCase = true)) {
            return PaymentInputParseAttempt.NoMatch
        }
        val payload = value.substring(LIGHTNING_PREFIX.length)
        return if (payload.isEmpty() || payload.startsWith("//") || ':' in payload) {
            PaymentInputParseAttempt.Rejected(FailureReason.Unrecognized)
        } else {
            PaymentInputParseAttempt.Reparse(payload)
        }
    }

    private fun parseLnurl(value: String): PaymentInputParseAttempt<Target, FailureReason> =
        when (val result = lnurlParser.parse(value)) {
            is LnurlParseResult.Parsed -> {
                val request = result.request
                val address =
                    if (request.inputFormat == LnurlInputFormat.LIGHTNING_ADDRESS) {
                        LightningAddress.parse(value)
                    } else {
                        null
                    }
                if (address != null) {
                    PaymentInputParseAttempt.Parsed(Target.LightningAddressTarget(address))
                } else {
                    PaymentInputParseAttempt.Parsed(Target.Lnurl(request))
                }
            }

            LnurlParseResult.Invalid ->
                PaymentInputParseAttempt.Rejected(FailureReason.InvalidLnurl)

            LnurlParseResult.UnsupportedSubprotocol ->
                PaymentInputParseAttempt.Rejected(FailureReason.UnsupportedLnurl)

            LnurlParseResult.NoMatch -> PaymentInputParseAttempt.NoMatch
        }

    private fun unwrapLightningFallback(
        value: String
    ): PaymentInputParseAttempt<Target, FailureReason> {
        if ('?' !in value || ':' !in value ||
            UNSUPPORTED_LNURL_PREFIXES.any { value.startsWith(it, ignoreCase = true) }
        ) {
            return PaymentInputParseAttempt.NoMatch
        }
        val query = value.substringAfter('?', missingDelimiterValue = "")
        val lightningValues = parseQuery(query).filter { it.first.equals("lightning", true) }
        return when (lightningValues.size) {
            0 -> PaymentInputParseAttempt.NoMatch
            1 -> PaymentInputParseAttempt.Reparse(lightningValues.single().second)
            else -> PaymentInputParseAttempt.Rejected(FailureReason.Unrecognized)
        }
    }

    private fun recognizeUnsupportedLnurl(
        value: String
    ): PaymentInputParseAttempt<Target, FailureReason> =
        if (UNSUPPORTED_LNURL_PREFIXES.any { value.startsWith(it, ignoreCase = true) }) {
            PaymentInputParseAttempt.Rejected(FailureReason.UnsupportedLnurl)
        } else {
            PaymentInputParseAttempt.NoMatch
        }

    private fun recognizeBolt12(value: String): PaymentInputParseAttempt<Target, FailureReason> =
        if (value.startsWith(BOLT12_OFFER_PREFIX, ignoreCase = true) &&
            OfferTypes.Offer.decode(value) is Try.Success
        ) {
            PaymentInputParseAttempt.Rejected(FailureReason.Bolt12)
        } else {
            PaymentInputParseAttempt.NoMatch
        }

    private fun recognizeBitcoin(value: String): PaymentInputParseAttempt<Target, FailureReason> {
        if (value.startsWith(BITCOIN_PREFIX, ignoreCase = true)) {
            return PaymentInputParseAttempt.Rejected(FailureReason.BitcoinAddress)
        }
        val isAddress =
            SUPPORTED_BITCOIN_CHAINS.any { chain ->
                Bitcoin.addressToPublicKeyScript(chain.chainHash, value).isRight
            }
        return if (isAddress) {
            PaymentInputParseAttempt.Rejected(FailureReason.BitcoinAddress)
        } else {
            PaymentInputParseAttempt.NoMatch
        }
    }

    private fun looksLikeBolt11(value: String): Boolean =
        BOLT11_PREFIXES.any { value.startsWith(it, ignoreCase = true) }

    private fun parseQuery(query: String): List<Pair<String, String>> {
        if (query.isEmpty()) return emptyList()
        return query.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val parts = pair.split('=', limit = 2)
            val key = parts.firstOrNull() ?: return@mapNotNull null
            runCatching {
                Uri.decode(key, convertPlus = true) to
                    Uri.decode(parts.getOrNull(1).orEmpty(), convertPlus = true)
            }.getOrNull()
        }
    }

    private fun PaymentInputParseAttempt<Target, FailureReason>.toParseResult(): ParseResult =
        when (this) {
            is PaymentInputParseAttempt.Parsed -> ParseResult.Success(value)

            is PaymentInputParseAttempt.Rejected -> ParseResult.Failure(reason)

            PaymentInputParseAttempt.NoMatch,
            is PaymentInputParseAttempt.Reparse
            -> ParseResult.Failure(FailureReason.Unrecognized)
        }

    private companion object {
        const val LIGHTNING_PREFIX = "lightning:"
        const val BITCOIN_PREFIX = "bitcoin:"
        const val BOLT12_OFFER_PREFIX = "lno1"

        val BOLT11_PREFIXES = listOf("lnbcrt", "lntbs", "lnbc", "lntb")
        val UNSUPPORTED_LNURL_PREFIXES = listOf("lnurlw://", "lnurlc://", "keyauth://")
        val SUPPORTED_BITCOIN_CHAINS =
            listOf(
                Chain.Mainnet,
                Chain.Testnet3,
                Chain.Testnet4,
                Chain.Signet,
                Chain.Regtest
            )
    }
}
