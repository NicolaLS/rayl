package xyz.lilsus.raylsuite.feature.paymenthub.ppq

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.lilsus.raylsuite.core.network.createHttpClient

sealed interface PpqResult<out T> {
    data class Ok<T>(val value: T) : PpqResult<T>

    /** ppq.ai rejected the API key. */
    data object InvalidKey : PpqResult<Nothing>

    /** No usable answer: offline, a server error, or a response this app cannot read. */
    data object Unreachable : PpqResult<Nothing>
}

/** The part of the ppq.ai API (https://ppq.ai/api-docs#topups) the Hub widget uses. */
interface PpqApi {
    /** The account's credit balance in US dollars. */
    suspend fun balanceUsd(apiKey: String): PpqResult<Double>

    /** A BOLT11 invoice that credits the account with [usd] once it is paid. */
    suspend fun createLightningTopUp(apiKey: String, usd: Double): PpqResult<String>

    fun close() {}
}

class KtorPpqApi(
    private val client: HttpClient = createHttpClient(),
    private val baseUrl: String = "https://api.ppq.ai"
) : PpqApi {
    override suspend fun balanceUsd(apiKey: String): PpqResult<Double> =
        request(apiKey, "/credits/balance", "{}", ::parsePpqBalance)

    override suspend fun createLightningTopUp(apiKey: String, usd: Double): PpqResult<String> =
        request(
            apiKey,
            "/topup/create/btc-lightning",
            buildJsonObject {
                put("amount", usd)
                put("currency", "USD")
            }.toString(),
            ::parsePpqInvoice
        )

    override fun close() = client.close()

    private suspend fun <T : Any> request(
        apiKey: String,
        path: String,
        body: String,
        parse: (JsonElement) -> T?
    ): PpqResult<T> = try {
        val response: HttpResponse = client.post(baseUrl + path) {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        when {
            response.status == HttpStatusCode.Unauthorized ||
                response.status == HttpStatusCode.Forbidden -> PpqResult.InvalidKey

            !response.status.isSuccess() -> PpqResult.Unreachable

            else -> parse(Json.parseToJsonElement(response.bodyAsText()))
                ?.let { PpqResult.Ok(it) }
                ?: PpqResult.Unreachable
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        PpqResult.Unreachable
    }
}

/*
 * ppq.ai documents these requests but not their response bodies, so the two readers below look
 * for the one value the widget needs instead of binding to a field layout.
 */

/** The first `balance` figure in the response, as a number or a numeric string. */
internal fun parsePpqBalance(body: JsonElement): Double? {
    val fields = body.fields().toList()
    val named = fields.firstOrNull { it.first.equals("balance", ignoreCase = true) }
        ?: fields.firstOrNull { it.first.contains("balance", ignoreCase = true) }
    return named?.second?.takeIf { it.isFinite() && it >= 0 }
}

/** The first BOLT11 invoice anywhere in the response. */
internal fun parsePpqInvoice(body: JsonElement): String? = body.strings()
    .map { it.trim().removePrefix("lightning:").removePrefix("LIGHTNING:") }
    .firstOrNull { it.startsWith("lnbc", ignoreCase = true) && it.length > 20 }

/** Every numeric field, outermost objects first. */
private fun JsonElement.fields(): Sequence<Pair<String, Double>> = sequence {
    val queue = ArrayDeque(listOf(this@fields))
    while (queue.isNotEmpty()) {
        when (val element = queue.removeFirst()) {
            is JsonObject -> element.forEach { (name, value) ->
                if (value is JsonPrimitive) {
                    value.content.toDoubleOrNull()?.let { yield(name to it) }
                } else {
                    queue += value
                }
            }

            is JsonArray -> queue.addAll(element)

            else -> Unit
        }
    }
}

private fun JsonElement.strings(): Sequence<String> = when (this) {
    is JsonObject -> values.asSequence().flatMap { it.strings() }
    is JsonArray -> asSequence().flatMap { it.strings() }
    is JsonPrimitive -> if (isString) sequenceOf(content) else emptySequence()
}
