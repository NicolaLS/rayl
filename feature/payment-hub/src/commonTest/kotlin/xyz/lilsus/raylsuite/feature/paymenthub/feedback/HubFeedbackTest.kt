package xyz.lilsus.raylsuite.feature.paymenthub.feedback

import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.feature.paymenthub.DefaultPaymentHubRepository
import xyz.lilsus.raylsuite.feature.paymenthub.WidgetHubViewModel
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController

@OptIn(ExperimentalCoroutinesApi::class)
class HubFeedbackTest {
    @Test
    fun theMessageGoesToTheBackendWithOnlyWhatTheFormHolds() = runTest {
        var url = ""
        var body = ""
        val api = KtorHubFeedbackApi("ios", "https://rayl.test") {
            HttpClient(
                MockEngine { request ->
                    assertEquals(HttpMethod.Post, request.method)
                    url = request.url.toString()
                    body = request.body.toByteArray().decodeToString()
                    respond("", HttpStatusCode.NoContent)
                }
            )
        }
        assertEquals(true, api.send(HubFeedbackKind.Widget, "  Zaps please ", " "))
        assertEquals("https://rayl.test/v1/feedback", url)
        assertEquals(
            Json.parseToJsonElement("""{"kind":"widget","platform":"ios","message":"Zaps please"}"""),
            Json.parseToJsonElement(body)
        )
        api.close()
    }

    @Test
    fun aMessageThatDidNotArriveIsNotReportedAsSent() = runTest {
        fun api(status: HttpStatusCode) = KtorHubFeedbackApi("android", "https://rayl.test") {
            HttpClient(MockEngine { respond("", status) })
        }
        assertEquals(false, api(HttpStatusCode.ServiceUnavailable).send(HubFeedbackKind.Idea, "hi", null))
        assertEquals(false, api(HttpStatusCode.NoContent).send(HubFeedbackKind.Idea, "   ", null))
        val offline = KtorHubFeedbackApi("android", "https://rayl.test") {
            HttpClient(MockEngine { error("offline") })
        }
        assertEquals(false, offline.send(HubFeedbackKind.Idea, "hi", null))
    }

    private class Fake(var result: Boolean = false) : HubFeedbackApi {
        val sent = mutableListOf<Triple<HubFeedbackKind, String, String?>>()
        override suspend fun send(kind: HubFeedbackKind, message: String, contact: String?): Boolean {
            sent += Triple(kind, message, contact)
            return result
        }
    }

    @Test
    fun theFormKeepsWhatWasWrittenUntilItWasSent() = runTest {
        val repo = DefaultPaymentHubRepository(MapSettings())
        val fake = Fake()
        val vm = WidgetHubViewModel(
            repo,
            PaymentHubController(repo, this),
            { "USD" },
            feedbackApi = fake,
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        vm.sendFeedback(HubFeedbackKind.Problem, "   ", "")
        advanceUntilIdle()
        assertEquals(emptyList(), fake.sent)

        vm.sendFeedback(HubFeedbackKind.Problem, "Chart is empty", "")
        advanceUntilIdle()
        assertEquals(true, vm.state.value.feedback.failed)
        assertEquals(0, vm.state.value.feedback.sentSerial)
        vm.clearFeedbackError()
        assertEquals(false, vm.state.value.feedback.failed)

        fake.result = true
        vm.sendFeedback(HubFeedbackKind.Problem, "Chart is empty", "me@example.com")
        advanceUntilIdle()
        assertEquals(false, vm.state.value.feedback.failed)
        assertEquals(1, vm.state.value.feedback.sentSerial)
        assertEquals(2, fake.sent.size)
        vm.clear()
    }
}
