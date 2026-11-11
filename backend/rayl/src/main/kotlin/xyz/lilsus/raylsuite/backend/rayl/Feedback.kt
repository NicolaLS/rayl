package xyz.lilsus.raylsuite.backend.rayl

import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.sql.Timestamp
import javax.sql.DataSource
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.raylapi.FeedbackCatalog
import xyz.lilsus.raylsuite.core.raylapi.FeedbackSubmission
import xyz.lilsus.raylsuite.core.raylapi.RaylApi

/** One piece of feedback as stored. No address, identifier or request detail is kept with it. */
data class FeedbackRow(
    val receivedAt: Long,
    val kind: String,
    val platform: String,
    val message: String,
    val contact: String?
)

fun interface FeedbackStore {
    suspend fun insert(row: FeedbackRow)
}

internal val FEEDBACK_RATE_LIMIT = RateLimitName("feedback")
internal val FEEDBACK_CLIENT_RATE_LIMIT = RateLimitName("feedback-client")
internal const val FEEDBACK_MAX_BODY_BYTES = 16L * 1024

/** At most this many messages per minute, over all senders. */
internal const val FEEDBACK_PER_MINUTE = 30

/** At most this many from one sender. */
internal const val FEEDBACK_PER_CLIENT_MINUTE = 5

private val feedbackJson = Json { ignoreUnknownKeys = true }

/**
 * Stores a message and answers 204; 400 for one the form could not have produced, 503 when it
 * cannot be kept, so the app can tell the tester to try again.
 */
internal fun Route.feedbackRoute(store: FeedbackStore?, nowSeconds: () -> Long) {
    rateLimited(perClient = FEEDBACK_CLIENT_RATE_LIMIT, overall = FEEDBACK_RATE_LIMIT) {
        route(RaylApi.FEEDBACK_PATH) {
            install(RequestBodyLimit) { bodyLimit { FEEDBACK_MAX_BODY_BYTES } }
            post {
                val submission = try {
                    feedbackJson.decodeFromString(
                        FeedbackSubmission.serializer(),
                        call.receiveText()
                    )
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }?.let(FeedbackCatalog::sanitize)
                if (submission == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }
                try {
                    checkNotNull(store) { "no database" }.insert(
                        FeedbackRow(
                            nowSeconds(),
                            submission.kind,
                            submission.platform,
                            submission.message,
                            submission.contact
                        )
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    // Nothing of the request is logged.
                    println("Storing feedback failed: ${failure::class.simpleName}")
                    call.respond(HttpStatusCode.ServiceUnavailable)
                    return@post
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/** Feedback in Postgres, kept until someone deletes it. `tools/feedback/feedback.sh` reads it. */
class PostgresFeedbackStore(private val dataSource: DataSource) : FeedbackStore {
    suspend fun createTables() = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> SCHEMA.forEach(statement::execute) }
        }
    }

    override suspend fun insert(row: FeedbackRow) {
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(INSERT).use { statement ->
                    statement.setTimestamp(1, Timestamp(row.receivedAt * 1_000))
                    statement.setString(2, row.kind)
                    statement.setString(3, row.platform)
                    statement.setString(4, row.message)
                    statement.setString(5, row.contact)
                    statement.executeUpdate()
                }
            }
        }
    }

    companion object {
        fun from(dataSource: HikariDataSource) = PostgresFeedbackStore(dataSource)

        private val SCHEMA = listOf(
            """
            create table if not exists feedback (
                id bigserial primary key,
                received_at timestamptz not null,
                kind text not null,
                platform text not null,
                message text not null,
                contact text
            )
            """
        )

        private const val INSERT =
            "insert into feedback (received_at, kind, platform, message, contact) values (?, ?, ?, ?, ?)"
    }
}
