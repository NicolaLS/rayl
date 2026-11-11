package xyz.lilsus.raylsuite.backend.rayl

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.Route
import java.net.Inet6Address
import java.net.InetAddress

/** Railway's edge puts the address a request came from here, replacing what the sender wrote. */
internal const val CLIENT_ADDRESS_HEADER = "X-Real-IP"

private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
private const val IPV6_MAX_LENGTH = 45
private const val IPV6_NETWORK_BYTES = 8

/**
 * Limits each sender, then all senders together. The order matters: a sender over its own limit
 * is refused before it can use up what the others share. The shared limit stays because many
 * addresses together could still fill the database.
 */
internal fun Route.rateLimited(
    perClient: RateLimitName,
    overall: RateLimitName,
    build: Route.() -> Unit
) = rateLimit(perClient) { rateLimit(overall, build) }

/**
 * Tells senders apart for rate limiting and nothing else: the key lives in the limiter's memory
 * for about a minute and is never logged or stored. Without the edge's header (a local run) it
 * is the connection's own address.
 */
internal fun ApplicationCall.clientKey(): String =
    request.headers.getAll(CLIENT_ADDRESS_HEADER)?.lastOrNull()?.let(::addressKey)
        ?: request.local.remoteAddress

/**
 * An IPv4 address as it is, an IPv6 address cut to its /64 network, because one subscriber
 * usually holds a whole /64 and could otherwise use a fresh address per request. Null for
 * anything that is not an address.
 */
internal fun addressKey(text: String): String? {
    val address = text.trim()
    if (IPV4.matches(address)) return address
    val looksLikeIpv6 = ':' in address &&
        address.length <= IPV6_MAX_LENGTH &&
        address.all { it == ':' || it == '.' || it.isAsciiHexDigit() }
    if (!looksLikeIpv6) return null
    // Brackets make this a literal to the JDK, so it is parsed and never looked up by name.
    val parsed = runCatching { InetAddress.getByName("[$address]") }.getOrNull() ?: return null
    return if (parsed is Inet6Address) {
        parsed.address.take(IPV6_NETWORK_BYTES).joinToString("") { "%02x".format(it) }
    } else {
        parsed.hostAddress
    }
}

private fun Char.isAsciiHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
