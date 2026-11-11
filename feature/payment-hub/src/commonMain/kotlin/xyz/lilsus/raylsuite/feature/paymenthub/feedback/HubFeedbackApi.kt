package xyz.lilsus.raylsuite.feature.paymenthub.feedback

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.network.createHttpClient
import xyz.lilsus.raylsuite.core.raylapi.FeedbackCatalog
import xyz.lilsus.raylsuite.core.raylapi.FeedbackSubmission
import xyz.lilsus.raylsuite.core.raylapi.RaylApi

/** What a tester can write to the developer from the Hub's "Add widget" screen. */
enum class HubFeedbackKind(val wire: String) {
    Widget(FeedbackCatalog.KIND_WIDGET),
    Idea(FeedbackCatalog.KIND_IDEA),
    Problem(FeedbackCatalog.KIND_PROBLEM),
    Other(FeedbackCatalog.KIND_OTHER)
}

interface HubFeedbackApi {
    /** True once the backend kept the message. */
    suspend fun send(kind: HubFeedbackKind, message: String, contact: String?): Boolean

    fun close() {}
}

/** Posts to the Rayl backend. The client is created on first use, never when the Hub opens. */
class KtorHubFeedbackApi(
    private val platform: String,
    private val baseUrl: String = RaylApi.BASE_URL,
    client: () -> HttpClient = ::createHttpClient
) : HubFeedbackApi {
    private val client by lazy(client)
    private var created = false

    override suspend fun send(kind: HubFeedbackKind, message: String, contact: String?): Boolean {
        val submission = FeedbackCatalog.sanitize(
            FeedbackSubmission(kind.wire, platform, message, contact)
        ) ?: return false
        return try {
            created = true
            client.post(baseUrl + RaylApi.FEEDBACK_PATH) {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(FeedbackSubmission.serializer(), submission))
            }.status.isSuccess()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        if (created) client.close()
    }
}
