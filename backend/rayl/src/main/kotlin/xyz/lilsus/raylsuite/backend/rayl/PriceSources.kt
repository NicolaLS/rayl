package xyz.lilsus.raylsuite.backend.rayl

import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An upstream that quotes bitcoin in one or more fiat currencies. */
interface PriceSource {
    val name: String

    /** Bitcoin's price keyed by currency code. Throws when the upstream fails. */
    suspend fun fetch(): Map<String, Double>
}

fun defaultPriceSources(http: HttpClient): List<PriceSource> = listOf(
    MempoolSource(http),
    CoinbaseSource(http),
    KrakenSource(http),
    BlockchainInfoSource(http)
)

class MempoolSource(private val http: HttpClient) : PriceSource {
    override val name = "mempool.space"

    override suspend fun fetch() = parse(http.getJson("https://mempool.space/api/v1/prices"))

    companion object {
        fun parse(body: JsonElement): Map<String, Double> = body.jsonObject
            .filterKeys { it != "time" }
            .numbers()
    }
}

class CoinbaseSource(private val http: HttpClient) : PriceSource {
    override val name = "coinbase"

    override suspend fun fetch() = parse(
        rates = http.getJson("https://api.coinbase.com/v2/exchange-rates?currency=BTC"),
        currencies = http.getJson("https://api.coinbase.com/v2/currencies")
    )

    companion object {
        /** The rates mix crypto with fiat; only the currencies Coinbase lists as fiat are kept. */
        fun parse(rates: JsonElement, currencies: JsonElement): Map<String, Double> {
            val fiat = currencies.jsonObject.getValue("data").jsonArray
                .map { it.jsonObject.getValue("id").jsonPrimitive.content }
                .toSet()
            return rates.jsonObject.getValue("data").jsonObject.getValue("rates").jsonObject
                .filterKeys { it in fiat }
                .numbers()
        }
    }
}

class KrakenSource(private val http: HttpClient) : PriceSource {
    override val name = "kraken"

    override suspend fun fetch() =
        parse(http.getJson("https://api.kraken.com/0/public/Ticker?pair=$PAIRS"))

    companion object {
        private val PAIRS =
            listOf("USD", "EUR", "GBP", "CAD", "CHF", "AUD", "JPY").joinToString(",") { "XBT$it" }

        /** Result keys vary (`XXBTZUSD`, `XBTCHF`), but the quote currency is always the last three letters. */
        fun parse(body: JsonElement): Map<String, Double> {
            val root = body.jsonObject
            val errors = root["error"] as? JsonArray
            check(errors.isNullOrEmpty()) { "kraken error $errors" }
            return root.getValue("result").jsonObject.entries.mapNotNull { (pair, ticker) ->
                val lastTrade = (ticker as? JsonObject)?.get("c") as? JsonArray
                lastTrade?.firstOrNull()?.number()?.let { pair.takeLast(3) to it }
            }.toMap()
        }
    }
}

class BlockchainInfoSource(private val http: HttpClient) : PriceSource {
    override val name = "blockchain.info"

    override suspend fun fetch() = parse(http.getJson("https://blockchain.info/ticker"))

    companion object {
        fun parse(body: JsonElement): Map<String, Double> =
            body.jsonObject.entries.mapNotNull { (code, ticker) ->
                (ticker as? JsonObject)?.get("last")?.number()?.let { code to it }
            }.toMap()
    }
}

/** Accepts JSON numbers and numeric strings, since upstreams use both. */
private fun JsonElement.number(): Double? = (this as? JsonPrimitive)?.content?.toDoubleOrNull()

private fun Map<String, JsonElement>.numbers(): Map<String, Double> =
    mapNotNull { (code, value) -> value.number()?.let { code to it } }.toMap()
