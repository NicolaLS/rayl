package xyz.lilsus.raylsuite.backend.rayl

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.lilsus.raylsuite.core.raylapi.FeedbackCatalog
import xyz.lilsus.raylsuite.core.raylapi.RaylApi

class FeedbackTest {
    private val stored = mutableListOf<FeedbackRow>()

    private fun feedbackTest(
        store: FeedbackStore? = FeedbackStore { stored += it },
        block: suspend ApplicationTestBuilder.() -> Unit
    ) = testApplication {
        application { raylModule(feedback = store, nowSeconds = { 1_794_321_462L }) { null } }
        block()
    }

    private suspend fun ApplicationTestBuilder.send(
        body: String,
        sender: String = "203.0.113.1"
    ): HttpResponse = client.post(RaylApi.FEEDBACK_PATH) {
        header(CLIENT_ADDRESS_HEADER, sender)
        setBody(body)
    }

    @Test
    fun keepsWhatTheFormSendsAndNothingElse() = feedbackTest {
        val response = send(
            """{"kind":"widget","platform":"ios","message":"  A Nostr zap widget please ","contact":" me@example.com ","extra":1}"""
        )
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(
            listOf(
                FeedbackRow(
                    1_794_321_462L,
                    "widget",
                    "ios",
                    "A Nostr zap widget please",
                    "me@example.com"
                )
            ),
            stored
        )
    }

    @Test
    fun aMessageWithoutContactIsKeptAnonymous() = feedbackTest {
        send("""{"kind":"idea","platform":"android","message":"Dark mode","contact":"  "}""")
        assertEquals(null, stored.single().contact)
    }

    @Test
    fun refusesWhatTheFormCouldNotHaveProduced() = feedbackTest {
        val tooLong = "x".repeat(FeedbackCatalog.MAX_MESSAGE_LENGTH + 1)
        listOf(
            "not json",
            """{"kind":"widget","platform":"ios","message":"   "}""",
            """{"kind":"spam","platform":"ios","message":"hi"}""",
            """{"kind":"widget","platform":"windows","message":"hi"}""",
            """{"kind":"widget","platform":"ios","message":"$tooLong"}""",
            """{"kind":"widget","platform":"ios","message":"hi","contact":"${"c".repeat(201)}"}"""
        ).forEachIndexed { case, body ->
            // One sender each, so the limit per sender stays out of this test.
            val status = send(body, sender = "203.0.113.$case").status
            assertEquals(HttpStatusCode.BadRequest, status, body.take(40))
        }
        assertEquals(emptyList(), stored)
    }

    @Test
    fun saysSoWhenTheMessageCannotBeKept() {
        val body = """{"kind":"other","platform":"ios","message":"hi"}"""
        feedbackTest(store = null) {
            assertEquals(HttpStatusCode.ServiceUnavailable, send(body).status)
        }
        feedbackTest(store = { error("database away") }) {
            assertEquals(HttpStatusCode.ServiceUnavailable, send(body).status)
        }
    }
}
