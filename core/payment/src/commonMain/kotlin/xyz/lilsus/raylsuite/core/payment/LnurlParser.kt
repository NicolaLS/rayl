package xyz.lilsus.raylsuite.core.payment

import fr.acinq.bitcoin.Bech32
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.parseUrl
import xyz.lilsus.raylsuite.core.model.LightningAddress

private const val BECH32_PREFIX = "lnurl1"
private const val LIGHTNING_PREFIX = "lightning:"
private const val LUD17_PAY_PREFIX = "lnurlp://"
private const val PAY_REQUEST_TAG = "payRequest"

enum class LnurlInputFormat {
    BECH32,
    LUD17_PAY,
    LIGHTNING_ADDRESS
}

/** Whether the input itself identifies LNURL-pay or requires endpoint resolution. */
enum class LnurlPayStatus {
    KNOWN_PAY,
    UNKNOWN
}

data class ParsedLnurl(
    val raw: String,
    val serviceUrl: String,
    val inputFormat: LnurlInputFormat,
    val payStatus: LnurlPayStatus
)

sealed interface LnurlParseResult {
    data class Parsed(val request: ParsedLnurl) : LnurlParseResult

    data object Invalid : LnurlParseResult

    data object UnsupportedSubprotocol : LnurlParseResult

    data object NoMatch : LnurlParseResult
}

interface LnurlParser {
    fun parse(value: String): LnurlParseResult

    fun parseOrNull(value: String): ParsedLnurl? =
        (parse(value) as? LnurlParseResult.Parsed)?.request
}

/** Parses the provider-neutral LNURL-pay input forms supported by the suite. */
class DefaultLnurlParser : LnurlParser {
    override fun parse(value: String): LnurlParseResult {
        val input = value.trim()
        if (input.isEmpty()) return LnurlParseResult.NoMatch
        if (input.length > MAX_INPUT_LENGTH) return LnurlParseResult.Invalid

        return when {
            input.startsWith(BECH32_PREFIX, ignoreCase = true) -> parseBech32(input)

            input.startsWith(LIGHTNING_PREFIX + BECH32_PREFIX, ignoreCase = true) ->
                parseBech32(input.substring(LIGHTNING_PREFIX.length))

            input.startsWith(LUD17_PAY_PREFIX, ignoreCase = true) -> parseLud17Pay(input)

            else ->
                parseLightningAddress(input)
                    ?.let(LnurlParseResult::Parsed)
                    ?: LnurlParseResult.NoMatch
        }
    }

    private fun parseLightningAddress(input: String): ParsedLnurl? {
        val candidate = input.removePrefixIgnoringCase(LIGHTNING_PREFIX)
        if (candidate.any { it == '/' || it == '?' || it == '#' }) return null
        if (candidate.count { it == '@' } != 1) return null

        val address = LightningAddress.parse(candidate) ?: return null
        val userPart = candidate.substringBefore('@').lowercase()
        if (!isValidLightningAddressUserPart(userPart)) return null

        val protocol =
            if (address.domain.endsWith(".onion", ignoreCase = true)) {
                URLProtocol.HTTP
            } else {
                URLProtocol.HTTPS
            }
        val serviceUrl =
            runCatching {
                URLBuilder(
                    protocol = protocol,
                    host = address.domain,
                    pathSegments = listOf("", ".well-known", "lnurlp", userPart)
                ).build()
            }.getOrNull() ?: return null

        return ParsedLnurl(
            raw = input,
            serviceUrl = serviceUrl.toString(),
            inputFormat = LnurlInputFormat.LIGHTNING_ADDRESS,
            payStatus = LnurlPayStatus.KNOWN_PAY
        )
    }

    private fun parseLud17Pay(input: String): LnurlParseResult {
        val lud17Url = parseUrl(input) ?: return LnurlParseResult.Invalid
        if (!lud17Url.protocol.name.equals("lnurlp", ignoreCase = true)) {
            return LnurlParseResult.Invalid
        }
        if (!isSafeUrlShape(lud17Url)) return LnurlParseResult.Invalid

        val tag = lud17Url.parameters["tag"]
        if (tag != null && tag != PAY_REQUEST_TAG) return LnurlParseResult.Invalid

        val serviceUrl =
            URLBuilder(lud17Url).apply {
                protocol =
                    if (lud17Url.host.endsWith(".onion", ignoreCase = true)) {
                        URLProtocol.HTTP
                    } else {
                        URLProtocol.HTTPS
                    }
            }.build()

        return LnurlParseResult.Parsed(
            ParsedLnurl(
                raw = input,
                serviceUrl = serviceUrl.toString(),
                inputFormat = LnurlInputFormat.LUD17_PAY,
                payStatus = LnurlPayStatus.KNOWN_PAY
            )
        )
    }

    private fun parseBech32(input: String): LnurlParseResult = try {
        val (humanReadablePart, bytes, encoding) = Bech32.decodeBytes(input)
        if (humanReadablePart != "lnurl" || encoding != Bech32.Encoding.Bech32) {
            return LnurlParseResult.Invalid
        }

        val decoded = bytes.decodeToString(throwOnInvalidSequence = true)
        val serviceUrl = parseUrl(decoded) ?: return LnurlParseResult.Invalid
        if (!isValidServiceUrl(serviceUrl)) return LnurlParseResult.Invalid

        val payStatus =
            when (serviceUrl.parameters["tag"]) {
                null -> LnurlPayStatus.UNKNOWN
                PAY_REQUEST_TAG -> LnurlPayStatus.KNOWN_PAY
                else -> return LnurlParseResult.UnsupportedSubprotocol
            }

        LnurlParseResult.Parsed(
            ParsedLnurl(
                raw = input,
                serviceUrl = serviceUrl.toString(),
                inputFormat = LnurlInputFormat.BECH32,
                payStatus = payStatus
            )
        )
    } catch (_: IllegalArgumentException) {
        LnurlParseResult.Invalid
    }

    private fun isValidServiceUrl(serviceUrl: Url): Boolean {
        if (!isSafeUrlShape(serviceUrl)) return false
        val isOnion = serviceUrl.host.endsWith(".onion", ignoreCase = true)
        return if (isOnion) {
            serviceUrl.protocol == URLProtocol.HTTP
        } else {
            serviceUrl.protocol == URLProtocol.HTTPS
        }
    }

    private fun isSafeUrlShape(url: Url): Boolean = url.host.isNotBlank() &&
        url.user.isNullOrEmpty() &&
        url.password.isNullOrEmpty() &&
        url.fragment.isEmpty()

    private fun isValidLightningAddressUserPart(userPart: String): Boolean =
        userPart.isNotEmpty() &&
            userPart.all { character ->
                character in 'a'..'z' ||
                    character in '0'..'9' ||
                    character == '-' ||
                    character == '_' ||
                    character == '.' ||
                    character == '+'
            }

    private fun String.removePrefixIgnoringCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

    private companion object {
        const val MAX_INPUT_LENGTH = 8 * 1024
    }
}
