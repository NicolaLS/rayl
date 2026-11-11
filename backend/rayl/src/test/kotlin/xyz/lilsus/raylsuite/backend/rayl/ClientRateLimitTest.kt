package xyz.lilsus.raylsuite.backend.rayl

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import xyz.lilsus.raylsuite.core.raylapi.RaylApi

class ClientRateLimitTest {
    private val stored = mutableListOf<FeedbackRow>()

    private fun rateLimitTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { raylModule(feedback = { stored += it }, telemetry = {}) { null } }
        block()
    }

    private suspend fun ApplicationTestBuilder.feedbackFrom(address: String?): HttpStatusCode =
        client.post(RaylApi.FEEDBACK_PATH) {
            address?.let { header(CLIENT_ADDRESS_HEADER, it) }
            setBody("""{"kind":"idea","platform":"ios","message":"hello"}""")
        }.status

    @Test
    fun oneSenderOverItsLimitIsRefusedWhileAnotherIsServed() = rateLimitTest {
        repeat(FEEDBACK_PER_CLIENT_MINUTE) {
            assertEquals(HttpStatusCode.NoContent, feedbackFrom("203.0.113.7"))
        }
        assertEquals(HttpStatusCode.TooManyRequests, feedbackFrom("203.0.113.7"))
        assertEquals(HttpStatusCode.NoContent, feedbackFrom("203.0.113.8"))
        assertEquals(FEEDBACK_PER_CLIENT_MINUTE + 1, stored.size)
    }

    @Test
    fun aSenderOverItsLimitUsesNoneOfWhatTheOthersShare() = rateLimitTest {
        // Far more than all senders together may send in a minute.
        repeat(FEEDBACK_PER_MINUTE * 2) { feedbackFrom("203.0.113.7") }

        // Everyone else still has the shared allowance, less what the first sender was let in for.
        val others = (FEEDBACK_PER_MINUTE - FEEDBACK_PER_CLIENT_MINUTE) / FEEDBACK_PER_CLIENT_MINUTE
        repeat(others) { sender ->
            repeat(FEEDBACK_PER_CLIENT_MINUTE) {
                assertEquals(HttpStatusCode.NoContent, feedbackFrom("198.51.100.$sender"))
            }
        }
    }

    @Test
    fun manySendersTogetherStillMeetTheSharedLimit() = rateLimitTest {
        val statuses = (0 until FEEDBACK_PER_MINUTE + 1).map { feedbackFrom("198.51.100.$it") }

        assertEquals(List(FEEDBACK_PER_MINUTE) { HttpStatusCode.NoContent }, statuses.dropLast(1))
        assertEquals(HttpStatusCode.TooManyRequests, statuses.last())
    }

    @Test
    fun invalidRequestsCountAgainstTheirSenderOnly() = rateLimitTest {
        repeat(FEEDBACK_PER_MINUTE * 2) {
            client.post(RaylApi.FEEDBACK_PATH) {
                header(CLIENT_ADDRESS_HEADER, "203.0.113.7")
                setBody("not json")
            }
        }
        assertEquals(HttpStatusCode.NoContent, feedbackFrom("203.0.113.8"))
    }

    @Test
    fun usageDataIsLimitedPerSenderToo() = rateLimitTest {
        suspend fun batchFrom(address: String) = client.post(RaylApi.TELEMETRY_PATH) {
            header(CLIENT_ADDRESS_HEADER, address)
            setBody("{}")
        }.status

        repeat(TELEMETRY_BATCHES_PER_CLIENT_MINUTE) {
            assertNotEquals(HttpStatusCode.TooManyRequests, batchFrom("203.0.113.7"))
        }
        assertEquals(HttpStatusCode.TooManyRequests, batchFrom("203.0.113.7"))
        assertNotEquals(HttpStatusCode.TooManyRequests, batchFrom("203.0.113.8"))
    }

    @Test
    fun anIpv6SubscriberIsOneSenderWhateverAddressItUses() = rateLimitTest {
        repeat(FEEDBACK_PER_CLIENT_MINUTE) {
            assertEquals(HttpStatusCode.NoContent, feedbackFrom("2001:db8:1:2::$it"))
        }
        assertEquals(
            HttpStatusCode.TooManyRequests,
            feedbackFrom("2001:db8:1:2:aaaa:bbbb:cccc:dddd")
        )
        assertEquals(HttpStatusCode.NoContent, feedbackFrom("2001:db8:1:3::1"))
    }

    @Test
    fun withoutAnAddressFromTheEdgeTheConnectionIsTheSender() = rateLimitTest {
        repeat(FEEDBACK_PER_CLIENT_MINUTE) {
            assertEquals(HttpStatusCode.NoContent, feedbackFrom(null))
        }
        assertEquals(HttpStatusCode.TooManyRequests, feedbackFrom(null))
        // Something that is not an address does not make a sender of its own either.
        assertEquals(HttpStatusCode.TooManyRequests, feedbackFrom("not-an-address"))
        assertEquals(HttpStatusCode.TooManyRequests, feedbackFrom("example.com"))
    }

    @Test
    fun addressesAreReadStrictly() {
        assertEquals("203.0.113.7", addressKey(" 203.0.113.7 "))
        assertEquals(addressKey("2001:db8::1"), addressKey("2001:0DB8:0:0:ffff:0:0:2"))
        assertNotEquals(addressKey("2001:db8::1"), addressKey("2001:db8:0:1::1"))
        assertEquals("203.0.113.7", addressKey("::ffff:203.0.113.7"))
        assertNull(addressKey(""))
        assertNull(addressKey("example.com"))
        assertNull(addressKey("abc.def"))
        assertNull(addressKey("203.0.113.7, 198.51.100.1"))
        assertNull(addressKey("1:2:3"))
        assertNull(addressKey("fe80::1%eth0"))
    }
}
