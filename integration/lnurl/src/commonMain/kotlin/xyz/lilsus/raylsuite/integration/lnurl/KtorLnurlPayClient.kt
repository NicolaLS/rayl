package xyz.lilsus.raylsuite.integration.lnurl

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.core.network.NetworkConnectivity
import xyz.lilsus.raylsuite.core.network.createHttpClient
import xyz.lilsus.raylsuite.core.payment.LnurlError
import xyz.lilsus.raylsuite.core.payment.LnurlPayClient
import xyz.lilsus.raylsuite.core.payment.LnurlPayMetadata
import xyz.lilsus.raylsuite.core.payment.LnurlPayParams
import xyz.lilsus.raylsuite.core.payment.LnurlResult

class KtorLnurlPayClient private constructor(
    private val networkConnectivity: NetworkConnectivity,
    clientFactory: () -> HttpClient,
    private val dispatcher: CoroutineDispatcher,
    private val json: Json,
    private val resolveHostAddresses: suspend (String) -> List<ByteArray>
) : LnurlPayClient {
    constructor(
        networkConnectivity: NetworkConnectivity,
        client: HttpClient,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        json: Json = Json { ignoreUnknownKeys = true },
        resolveHostAddresses: suspend (String) -> List<ByteArray> = ::resolveLnurlHost
    ) : this(
        networkConnectivity = networkConnectivity,
        clientFactory = { client },
        dispatcher = dispatcher,
        json = json,
        resolveHostAddresses = resolveHostAddresses
    )

    constructor(
        networkConnectivity: NetworkConnectivity,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        json: Json = Json { ignoreUnknownKeys = true },
        resolveHostAddresses: suspend (String) -> List<ByteArray> = ::resolveLnurlHost
    ) : this(
        networkConnectivity = networkConnectivity,
        clientFactory = ::createHttpClient,
        dispatcher = dispatcher,
        json = json,
        resolveHostAddresses = resolveHostAddresses
    )

    private val baseClientDelegate = lazy(clientFactory)
    private val lnurlClientDelegate = lazy {
        baseClientDelegate.value.config {
            followRedirects = false
            expectSuccess = false
            install(HttpRequestRetry) { maxRetries = 0 }
        }
    }
    private val lnurlClient: HttpClient by lnurlClientDelegate

    fun close() {
        if (lnurlClientDelegate.isInitialized()) lnurlClientDelegate.value.close()
        if (baseClientDelegate.isInitialized()) baseClientDelegate.value.close()
    }

    override suspend fun fetchPayParams(endpoint: String): LnurlResult<LnurlPayParams> =
        withContext(dispatcher) {
            request {
                val url = requirePublicDestination(endpoint.trim())
                val raw = readResponse(url)
                parsePayParams(raw, url.host)
            }
        }

    override suspend fun fetchPayParams(address: LightningAddress): LnurlResult<LnurlPayParams> =
        fetchPayParams(buildAddressUrl(address))

    override suspend fun requestInvoice(
        callback: String,
        amountMsats: Long,
        comment: String?
    ): LnurlResult<String> = withContext(dispatcher) {
        request {
            if (amountMsats <= 0) fail("LNURL amount must be positive")
            val url = requirePublicDestination(callback)
            val raw = readResponse(url) {
                // Replace any server-supplied amount/comment, so the callback gets exactly the approved values.
                this.url.parameters.remove("amount")
                this.url.parameters.remove("comment")
                this.url.parameters.append("amount", amountMsats.toString())
                if (!comment.isNullOrBlank()) this.url.parameters.append("comment", comment)
            }
            val element = parseObject(raw)
            element["pr"].stringValue()?.takeIf(String::isNotBlank)
                ?: fail("LNURL invoice is missing")
        }
    }

    private suspend fun <T> request(block: suspend () -> T): LnurlResult<T> {
        if (!networkConnectivity.isNetworkAvailable()) {
            return LnurlResult.Error(
                LnurlError.NetworkUnavailable
            )
        }
        return try {
            LnurlResult.Success(block())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (cause: LnurlProtocolException) {
            LnurlResult.Error(LnurlError.Protocol(cause.message))
        } catch (cause: Throwable) {
            // Transport exceptions can contain URLs, query comments, or server response bodies.
            // Keep these out of display state and diagnostics, including the retained exception cause.
            when {
                !networkConnectivity.isNetworkAvailable() -> LnurlResult.Error(
                    LnurlError.NetworkUnavailable
                )

                cause is kotlinx.io.IOException -> LnurlResult.Error(
                    LnurlError.Protocol("Failed to reach LNURL service")
                )

                else -> LnurlResult.Error(LnurlError.Unexpected("LNURL request failed"))
            }
        }
    }

    private suspend fun requirePublicDestination(value: String): Url {
        val url = parseLnurlDestination(value) ?: fail("LNURL requires a public HTTPS destination")
        val addresses = resolveHostAddresses(url.host)
        if (addresses.isEmpty() || addresses.any { !isPublicLnurlAddress(it) }) {
            fail("LNURL destination does not resolve to public addresses")
        }
        return url
    }

    private suspend fun readResponse(
        url: Url,
        configure: HttpRequestBuilder.() -> Unit = {
        }
    ): String = lnurlClient.prepareGet(url, configure).execute { response ->
        if (response.status.value in 300..399) fail("LNURL redirects are not supported")
        if (response.status.value !in
            200..299
        ) {
            fail("LNURL service returned an unsuccessful response")
        }
        val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
        if (contentLength > MAX_RESPONSE_BYTES) {
            fail("LNURL response is too large")
        }
        val channel = response.bodyAsChannel()
        val initialCapacity = if (contentLength in 1..MAX_RESPONSE_BYTES) {
            (contentLength.toInt() + 1).coerceAtLeast(INITIAL_RESPONSE_BUFFER_BYTES)
        } else {
            INITIAL_RESPONSE_BUFFER_BYTES
        }
        var bytes = ByteArray(initialCapacity.coerceAtMost(MAX_RESPONSE_BYTES + 1))
        var size = 0
        while (true) {
            if (size == bytes.size) {
                if (bytes.size > MAX_RESPONSE_BYTES) break
                val nextSize = (bytes.size * 2).coerceAtMost(MAX_RESPONSE_BYTES + 1)
                bytes = bytes.copyOf(nextSize)
            }
            val count = channel.readAvailable(bytes, size, bytes.size - size)
            if (count < 0) break
            size += count
        }
        if (size > MAX_RESPONSE_BYTES) fail("LNURL response is too large")
        try {
            bytes.decodeToString(endIndex = size, throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            fail("LNURL response is not UTF-8")
        }
    }

    private suspend fun parsePayParams(raw: String, domain: String): LnurlPayParams {
        val element = parseObject(raw)
        if (element["tag"].stringValue() != "payRequest") fail("LNURL tag is not payRequest")
        val callback = element["callback"].stringValue() ?: fail("LNURL pay callback missing")
        val callbackUrl =
            parseLnurlDestination(callback) ?: fail("LNURL requires a public HTTPS destination")
        if (!callbackUrl.host.equals(domain, ignoreCase = true)) {
            val addresses = resolveHostAddresses(callbackUrl.host)
            if (addresses.isEmpty() || addresses.any { !isPublicLnurlAddress(it) }) {
                fail("LNURL destination does not resolve to public addresses")
            }
        }
        val maxSendable =
            element["maxSendable"].integerValue() ?: fail("LNURL maxSendable must be an integer")
        val minSendable =
            element["minSendable"].integerValue() ?: fail("LNURL minSendable must be an integer")
        if (maxSendable <= 0 || minSendable <= 0 ||
            maxSendable < minSendable
        ) {
            fail("LNURL sendable amounts invalid")
        }
        val metadataRaw = element["metadata"].stringValue() ?: fail("LNURL metadata missing")
        val metadata = parseMetadata(metadataRaw)
        val commentAllowed = element["commentAllowed"]?.let {
            (it as? JsonPrimitive)?.takeUnless(
                JsonPrimitive::isString
            )?.intOrNull?.takeIf { count ->
                count >=
                    0
            }
                ?: fail("LNURL comment limit must be a non-negative integer")
        }
        return LnurlPayParams(
            callback = callback,
            minSendable = minSendable,
            maxSendable = maxSendable,
            metadataRaw = metadataRaw,
            metadata = metadata,
            commentAllowed = commentAllowed,
            domain = domain
        )
    }

    private fun parseObject(raw: String): JsonObject {
        val element = parseJson(raw) as? JsonObject ?: fail("LNURL response must be an object")
        if (element["status"].stringValue()?.equals("ERROR", ignoreCase = true) == true) {
            fail("LNURL service rejected the request")
        }
        return element
    }

    private fun parseMetadata(raw: String): LnurlPayMetadata {
        val metadata = parseJson(raw) as? JsonArray ?: fail("LNURL metadata must be an array")
        val knownValues = mutableMapOf<String, String>()
        for (entry in metadata) {
            val array = entry as? JsonArray ?: fail("LNURL metadata entry must be an array")
            val type = array.firstOrNull().stringValue() ?: fail("LNURL metadata type must be text")
            // Future metadata types can carry any JSON value; only known text/image fields are interpreted.
            if (type !in METADATA_TYPES) continue
            val value =
                array.getOrNull(1).stringValue() ?: fail("LNURL metadata value must be text")
            if (knownValues.put(type, value) !=
                null
            ) {
                fail("LNURL metadata contains duplicate entries")
            }
        }
        val plainText = knownValues["text/plain"]?.takeIf { value ->
            value.any {
                !it.isWhitespace() && !it.isISOControl() && it.code !in 0x200b..0x200f &&
                    it.code !in 0x202a..0x202e &&
                    it.code !in 0x2060..0x2069 &&
                    it.code != 0xfeff
            }
        } ?: fail("LNURL metadata requires a description")
        if (knownValues["image/png;base64"] != null && knownValues["image/jpeg;base64"] != null) {
            fail("LNURL metadata contains multiple images")
        }
        return LnurlPayMetadata(
            plainText = plainText,
            longText = knownValues["text/long-desc"],
            imagePng = knownValues["image/png;base64"],
            imageJpeg = knownValues["image/jpeg;base64"],
            identifier = knownValues["text/identifier"],
            email = knownValues["text/email"],
            tag = knownValues["text/tag"]
        )
    }

    private fun parseJson(raw: String): JsonElement {
        // A small body can still contain enough nested arrays to exhaust the native parser stack.
        var depth = 0
        var inString = false
        var escaped = false
        raw.forEach { character ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true

                    '[', '{' -> if (++depth >
                        MAX_JSON_DEPTH
                    ) {
                        fail("LNURL response is too deeply nested")
                    }

                    ']', '}' -> if (--depth < 0) fail("LNURL response is not valid JSON")
                }
            }
        }
        return runCatching {
            json.parseToJsonElement(raw)
        }.getOrElse { fail("LNURL response is not valid JSON") }
    }

    private fun buildAddressUrl(address: LightningAddress): String =
        URLBuilder("https://${address.domain.lowercase()}").apply {
            appendPathSegments(
                ".well-known",
                "lnurlp",
                address.username + (address.tag?.takeIf(String::isNotEmpty)?.let { "+$it" } ?: "")
            )
        }.buildString()

    private fun JsonElement?.stringValue(): String? =
        (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonElement?.integerValue(): Long? =
        (this as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull

    private fun fail(message: String): Nothing = throw LnurlProtocolException(message)
}

private class LnurlProtocolException(message: String) : Exception(message)

private const val INITIAL_RESPONSE_BUFFER_BYTES = 4 * 1024
private const val MAX_RESPONSE_BYTES = 256 * 1024
private const val MAX_JSON_DEPTH = 32
private val METADATA_TYPES =
    setOf(
        "text/plain",
        "text/long-desc",
        "image/png;base64",
        "image/jpeg;base64",
        "text/identifier",
        "text/email",
        "text/tag"
    )
