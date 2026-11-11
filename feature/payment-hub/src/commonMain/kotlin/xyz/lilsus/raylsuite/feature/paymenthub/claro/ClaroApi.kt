package xyz.lilsus.raylsuite.feature.paymenthub.claro

import fr.acinq.lightning.payment.Bolt11Invoice
import io.ktor.client.HttpClient
import io.ktor.client.plugins.retry
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.lilsus.raylsuite.core.network.createHttpClient
import xyz.lilsus.raylsuite.feature.paymenthub.platformCurrentTimeMillis

sealed interface ClaroResult<out T> {
    data class Ok<T>(val value: T) : ClaroResult<T>

    /** Claro sells nothing to this number online: not a Claro line, or not one that buys packages. */
    data object NotEligible : ClaroResult<Nothing>

    /** No usable answer: offline, maintenance, or a response this app does not trust. */
    data object Unavailable : ClaroResult<Nothing>
}

/** A number Claro accepted, with what its catalogue and orders are asked for. */
data class ClaroLine(
    /** Eight national digits, as [claroPhone] returns them. */
    val phone: String,
    internal val lineOfBusiness: String,
    internal val companyId: JsonElement? = null,
    internal val corpFtu: JsonElement = JsonPrimitive(false)
)

/** A top-up or package Claro offers one number. */
data class ClaroProduct(
    val id: String,
    val name: String,
    /** What the package holds and how long it lasts, in Claro's own words. */
    val detail: String?,
    val priceCents: Long,
    val topUp: Boolean,
    /** Claro's catalogue entry. An order sends it back as it came. */
    internal val item: JsonObject
)

data class ClaroCatalogue(val line: ClaroLine, val products: List<ClaroProduct>)

/** An unpaid invoice Claro's payment provider issued for one order. */
data class ClaroInvoice(
    val bolt11: String,
    val amountSats: Long,
    /** Claro's own name for the order; its delivery is asked about by it. */
    val transactionId: String,
    /** The checkout at Claro's payment provider; the payment is asked about by it. */
    val checkout: String
)

/** The two names one order goes by: Claro's, and its payment provider's. */
internal data class ClaroOrderReference(val transactionId: String, val checkout: String)

/** What Claro's payment provider says about a checkout. */
enum class ClaroPayment {
    Paid,

    /** Closed without a payment. */
    Expired,

    /** Still open, or no answer. */
    Unknown
}

/** What Claro says about an order that was paid. */
enum class ClaroDelivery {
    Delivered,

    /** Claro answered, and did not confirm the delivery. */
    NotConfirmed,

    /** Offline, or Claro is not answering. */
    NoAnswer
}

/**
 * Claro El Salvador's own web shop API (paquetes2) and its Lightning checkout at BTCPaths, called
 * straight from the phone. Neither needs an account. Nothing here pays: an order only exists to
 * get the invoice the wallet then pays.
 */
interface ClaroApi {
    /** Checks [phone] with Claro and reads what it sells to that number. */
    suspend fun catalogue(phone: String): ClaroResult<ClaroCatalogue>

    /** Orders [product] for [line] and returns the invoice that pays for it. */
    suspend fun invoice(line: ClaroLine, product: ClaroProduct): ClaroResult<ClaroInvoice>

    /** Whether the [checkout] of an order was paid. Reads only. */
    suspend fun payment(checkout: String): ClaroPayment

    /** Whether Claro delivered a paid order, which is what its own shop asks after a payment. */
    suspend fun delivery(phone: String, transactionId: String): ClaroDelivery

    fun close() {}
}

/** The terms of Claro's web shop, which sells and delivers what this client orders. */
const val CLARO_TERMS_URL = "https://paquetes2.miclaro.com.sv/portal/terms"

/**
 * The eight national digits Claro's API takes, from a number as people write it: with or without
 * +503, spaces and dashes. Null when it is not a Salvadoran number.
 */
fun claroPhone(input: String): String? = PHONE.matchEntire(
    input.filterNot { it.isWhitespace() || it in "()-" }
)?.groupValues?.get(1)

/** A national number as Salvadorans write it: 7123 4567. */
fun claroPhoneLabel(phone: String): String = phone.chunked(4).joinToString(" ")

class KtorClaroApi(
    client: HttpClient = createHttpClient(),
    private val claroUrl: String = "https://api.paquetes2.miclaro.com.sv",
    private val checkoutUrl: String = "https://pl.btcpaths.com",
    private val nowSeconds: () -> Long = { platformCurrentTimeMillis() / 1000 }
) : ClaroApi {
    // An answer is only ever taken from the host that was asked.
    private val client = client.config { followRedirects = false }

    override suspend fun catalogue(phone: String): ClaroResult<ClaroCatalogue> {
        val settings = send("$claroUrl/config")?.takeIf { it.ok }?.body.settings()
            ?: return ClaroResult.Unavailable
        if (settings["MAINTENANCE_GLOBAL_MCE"] == "1" || settings["MAINTENANCE_PACKAGES"] == "1") {
            return ClaroResult.Unavailable
        }
        // The lines of business the web shop sells to; a contract line, for one, is not among them.
        val sellsTo = settings["LOB_MCE"]?.let { encoded ->
            runCatching { (Json.parseToJsonElement(encoded) as JsonArray).map { it.wholeNumber() } }
                .getOrNull()
        } ?: return ClaroResult.Unavailable

        val validation = send(
            "$claroUrl/validateNumber",
            buildJsonObject { put("claroNumber", phone) },
            country = true
        ) ?: return ClaroResult.Unavailable
        if (validation.status in 400..499) return ClaroResult.NotEligible
        if (!validation.ok) return ClaroResult.Unavailable
        val profile = validation.body.at("body", "data") as? JsonObject
            ?: return ClaroResult.Unavailable
        val lineOfBusiness = profile["LineOfBusiness"].wholeNumber()
        if (profile["AcknowledgementIndicator"].text() != "SUCCESS" ||
            lineOfBusiness == null ||
            lineOfBusiness !in sellsTo
        ) {
            return ClaroResult.NotEligible
        }
        val line = ClaroLine(
            phone = phone,
            lineOfBusiness = lineOfBusiness.toString(),
            companyId = profile["companyId"],
            corpFtu = profile["corp_ftu"]?.takeIf { it.truthy() } ?: JsonPrimitive(false)
        )

        val offers = send(
            "$claroUrl/offerts/getOfferts",
            buildJsonObject {
                put("phoneNumber", phone)
                put("LineOfBusiness", line.lineOfBusiness)
                line.companyId?.let { put("companyId", it) }
                put("corp_ftu", line.corpFtu)
            },
            country = true
        )?.takeIf { it.ok }?.body
        val products = claroProducts(offers) ?: return ClaroResult.Unavailable
        return ClaroResult.Ok(ClaroCatalogue(line, products))
    }

    override suspend fun invoice(
        line: ClaroLine,
        product: ClaroProduct
    ): ClaroResult<ClaroInvoice> {
        val item = product.item
        val price = item["price"].text() ?: return ClaroResult.Unavailable
        // The web shop starts every purchase as a card order and only then switches it to bitcoin.
        val transaction = send(
            "$claroUrl/paymentsMce",
            buildJsonObject {
                put("xmei", "")
                put("phoneNumber", line.phone)
                put("amount", price)
                put("channel", if (product.topUp) TOP_UP_CHANNEL else PACKAGE_CHANNEL)
                put("lineofbusiness", line.lineOfBusiness)
                // Claro's own spelling. The whole catalogue entry travels as a JSON string.
                put("paymetPackageDescription", item.toString())
                if (!product.topUp) {
                    put("packageCode", item.getValue("packId"))
                    put(
                        "packageDescription",
                        item["packageName"].text().orEmpty() + " " +
                            item["packageDescription"].text().orEmpty()
                    )
                }
            }
        )?.takeIf { it.ok }?.body.at("idTransaction")?.takeUnless { it.text().isNullOrEmpty() }
            ?: return ClaroResult.Unavailable

        val handoff = send(
            "$claroUrl/bitcoinPayment",
            buildJsonObject {
                put("numero", line.phone)
                put("transaccionId", transaction)
                put("precio", price)
                item["packageDescription"]?.let { put("package", it) }
            },
            country = true
        )?.takeIf { it.ok }?.body
        if (handoff.at("ok") != JsonPrimitive(true)) return ClaroResult.Unavailable
        val slug = claroCheckoutSlug(handoff.at("data", "url").text())
            ?: return ClaroResult.Unavailable

        val checkout = send("$checkoutUrl/api/v1/checkouts?slug=$slug", browser = true)
            ?.takeIf { it.ok }?.body as? JsonObject
            ?: return ClaroResult.Unavailable
        val order = ClaroOrderReference(transaction.text().orEmpty(), slug)
        return claroCheckoutInvoice(checkout, order, line.phone, product.priceCents, nowSeconds())
            ?.let { ClaroResult.Ok(it) }
            ?: ClaroResult.Unavailable
    }

    override suspend fun payment(checkout: String): ClaroPayment {
        val status = send("$checkoutUrl/api/v1/checkouts?slug=$checkout", browser = true)
            ?.takeIf { it.ok }?.body.at("status").text()?.uppercase()
        return when (status) {
            "FINALIZED" -> ClaroPayment.Paid
            "EXPIRED" -> ClaroPayment.Expired
            else -> ClaroPayment.Unknown
        }
    }

    override suspend fun delivery(phone: String, transactionId: String): ClaroDelivery {
        val reply = send(
            "$claroUrl/bitcoinPayment/check-provision",
            buildJsonObject {
                put("numero", phone)
                put("transaccionId", transactionId)
            },
            country = true
        )
        return when {
            reply == null || reply.status >= 500 -> ClaroDelivery.NoAnswer

            reply.ok && reply.body.at("ok") == JsonPrimitive(true) -> ClaroDelivery.Delivered

            // Whatever else Claro says, it is not a confirmation.
            else -> ClaroDelivery.NotConfirmed
        }
    }

    override fun close() = client.close()

    private class Reply(val status: Int, val body: JsonElement?) {
        val ok get() = status in 200..299
    }

    /** One request, never repeated: a second order must be the user's decision. Null offline. */
    private suspend fun send(
        url: String,
        body: JsonObject? = null,
        country: Boolean = false,
        browser: Boolean = false
    ): Reply? = try {
        val response = client.request(url) {
            method = if (body == null) HttpMethod.Get else HttpMethod.Post
            retry { noRetry() }
            if (country) header("country", "SV")
            if (browser) {
                // BTCPaths answers 403 to a client that does not look like its own checkout page.
                header(HttpHeaders.Accept, "application/json")
                header(HttpHeaders.Referrer, "$checkoutUrl/")
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Rayl)")
            }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        Reply(
            response.status.value,
            runCatching { Json.parseToJsonElement(response.bodyAsText()) }.getOrNull()
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }
}

/**
 * Every top-up and package in Claro's catalogue response, in Claro's order. Entries this app
 * cannot order are left out. Null when the response is not a catalogue.
 */
internal fun claroProducts(offers: JsonElement?): List<ClaroProduct>? {
    if (offers.at("data", "AcknowledgementIndicator").text() == "ERROR") return null
    val groups = offers.at("data", "package") as? JsonArray ?: return null
    val products = LinkedHashMap<String, ClaroProduct>()
    for (group in groups) {
        val type = group.at("type").text()?.takeIf { it in ORDERABLE_TYPES } ?: continue
        for (category in group.at("category") as? JsonArray ?: continue) {
            val items = (category.at("items") as? JsonArray).orEmpty()
                .filterIsInstance<JsonObject>()
                .sortedBy { it["secuencia"].text()?.toDoubleOrNull() ?: Double.MAX_VALUE }
            for (raw in items) {
                val product = claroProduct(type, category.at("nombre"), raw) ?: continue
                // One package can be listed under several categories.
                if (product.id !in products) products[product.id] = product
            }
        }
    }
    return products.values.toList()
}

private fun claroProduct(type: String, category: JsonElement?, raw: JsonObject): ClaroProduct? {
    val topUp = type == TOP_UP_TYPE
    val sku = raw["sku"].text().orEmpty()
    val packId = raw["packId"].text().orEmpty()
    // A package is ordered by its packId; a top-up by its price alone.
    if ((sku.isEmpty() && packId.isEmpty()) || (!topUp && packId.isEmpty())) return null
    val cents = raw["price"].text()?.toDoubleOrNull()?.let { (it * 100).roundToLong() }
        ?.takeIf { it in 1 until BILLING_FLOW_CENTS }
        ?: return null
    // The price as Claro's shop writes it in an order: "5", or "5.50".
    val price = if (cents % 100 == 0L) {
        (cents / 100).toString()
    } else {
        "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
    }
    return ClaroProduct(
        id = "$type:$sku:$packId",
        name = raw["packageName"].text()?.trim()?.takeIf(String::isNotEmpty)
            ?: sku.ifEmpty { packId },
        detail = listOfNotNull(
            raw["packageDescription"].text()?.trim()?.takeIf(String::isNotEmpty),
            raw["expiry"].text()?.trim()?.takeIf(String::isNotEmpty)
        ).joinToString(" · ").takeIf(String::isNotEmpty),
        priceCents = cents,
        topUp = topUp,
        // What the shop serializes at checkout: the entry as it came, with the price rewritten,
        // the category it was listed under, and without its icon.
        item = buildJsonObject {
            raw.forEach { (key, value) ->
                if (key !=
                    "redesSocialesIcon"
                ) {
                    put(
                        key,
                        if (key ==
                            "price"
                        ) {
                            JsonPrimitive(price)
                        } else {
                            value
                        }
                    )
                }
            }
            if (category != null) put("categoryName", category)
        }
    )
}

/** The checkout the shop would send the browser to, as the slug BTCPaths' API looks it up by. */
internal fun claroCheckoutSlug(url: String?): String? = CHECKOUT_URL.matchEntire(url.orEmpty())
    ?.groupValues?.drop(1)?.firstOrNull(String::isNotEmpty)

/**
 * The invoice of a BTCPaths [checkout], if it is the one this order asked for: still open, for
 * the same number and US dollar price, and a mainnet invoice over exactly the bitcoin amount the
 * checkout names, with time left to pay it. Null otherwise.
 */
internal fun claroCheckoutInvoice(
    checkout: JsonObject,
    order: ClaroOrderReference,
    phone: String,
    priceCents: Long,
    nowSeconds: Long
): ClaroInvoice? {
    val status = checkout["status"].text()?.uppercase() ?: return null
    if (status == "FINALIZED" || status == "EXPIRED") return null
    if (checkout.at("from_currency", "symbol").text() != "USD" ||
        checkout.at("from_currency", "amount").text()?.decimalUnits(2) != priceCents ||
        checkout["phone"].text()?.let(::claroPhone) != phone
    ) {
        return null
    }
    val bolt11 = checkout["address"].text()?.lowercase()?.removePrefix("lightning:") ?: return null
    val invoice = runCatching { Bolt11Invoice.read(bolt11).get() }.getOrNull() ?: return null
    val msat = invoice.amount?.msat?.takeIf { it > 0 } ?: return null
    if (invoice.prefix != "lnbc" ||
        checkout.at("to_currency", "symbol").text() != "BTC" ||
        checkout.at("to_currency", "amount").text()?.decimalUnits(MSAT_DECIMALS) != msat
    ) {
        return null
    }
    // BTCPaths counts its own checkout down, independently of the invoice's signed expiry.
    val remaining = checkout["expiration_seconds"].text()?.toDoubleOrNull()?.toLong() ?: return null
    val expiresAt = min(
        invoice.timestampSeconds + (invoice.expirySeconds ?: DEFAULT_EXPIRY_SECONDS),
        nowSeconds + remaining
    )
    if (expiresAt - nowSeconds <= MIN_SECONDS_TO_PAY) return null
    return ClaroInvoice(bolt11, (msat + 999) / 1000, order.transactionId, order.checkout)
}

/**
 * A decimal figure, which JSON may write in scientific notation, as a whole number of
 * 10^-[decimals] units. Null when it is not a number or does not come out whole: amounts are
 * compared exactly, never through a floating-point product.
 */
internal fun String.decimalUnits(decimals: Int): Long? {
    val match = DECIMAL.matchEntire(trim()) ?: return null
    val fraction = match.groupValues[2]
    val exponent = match.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return null
    val shift = decimals + exponent - fraction.length
    val digits = match.groupValues[1] + fraction
    return when {
        shift !in -MAX_SHIFT..MAX_SHIFT -> null
        shift >= 0 -> (digits + "0".repeat(shift)).toLongOrNull()
        digits.takeLast(-shift).any { it != '0' } -> null
        else -> digits.dropLast(-shift).ifEmpty { "0" }.toLongOrNull()
    }
}

private fun JsonElement?.settings(): Map<String, String>? =
    (at("body", "data") as? JsonArray)?.mapNotNull { entry ->
        val key = entry.at("key").text() ?: return@mapNotNull null
        entry.at("value").text()?.let { key to it }
    }?.toMap()

private fun JsonElement?.at(vararg path: String): JsonElement? =
    path.fold(this) { element, key -> (element as? JsonObject)?.get(key) }

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }
    ?.content

private fun JsonElement?.wholeNumber(): Long? =
    text()?.trim()?.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toLong()

private fun JsonElement.truthy(): Boolean = when (val text = text()) {
    null -> this !is JsonNull
    else -> text.isNotEmpty() && text != "false" && text != "0"
}

private val PHONE = Regex("""(?:\+?503)?(\d{8})""")
private val DECIMAL = Regex("""(\d+)(?:\.(\d*))?(?:[eE]([+-]?\d+))?""")
private val CHECKOUT_URL = Regex(
    """https://if\.btcpaths\.com/([a-fA-F0-9]{32})/?""" +
        """|https://pl\.btcpaths\.com/(?:api/v1/checkouts/?)?\?slug=([a-fA-F0-9]{32})"""
)

/** Top-ups (recargas); the other groups are packages and promotions. */
private const val TOP_UP_TYPE = "R"
private val ORDERABLE_TYPES = setOf("P", "PM", "PP", TOP_UP_TYPE)
private const val TOP_UP_CHANNEL = 1
private const val PACKAGE_CHANNEL = 4

/** From USD 2,500 Claro sends a purchase to a separate billing flow this app does not speak. */
private const val BILLING_FLOW_CENTS = 250_000L

/** A bitcoin amount has eleven decimals of millisatoshi. */
private const val MSAT_DECIMALS = 11
private const val MAX_SHIFT = 18
private const val DEFAULT_EXPIRY_SECONDS = 3600L

/** An invoice about to lapse is not handed to the wallet. */
private const val MIN_SECONDS_TO_PAY = 30L
