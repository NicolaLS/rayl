package xyz.lilsus.raylsuite.feature.paymenthub.switches

import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.network.createHttpClient
import xyz.lilsus.raylsuite.core.raylapi.HubWidgetSwitches
import xyz.lilsus.raylsuite.core.raylapi.RaylApi

/** Asks the Rayl backend which widgets are turned off. Null when it gave no usable answer. */
fun interface HubSwitchesApi {
    suspend fun disabledWidgets(): Set<String>?
}

/** Reads [RaylApi.HUB_WIDGETS_PATH]. The client is created on first use, never when the Hub opens. */
class KtorHubSwitchesApi(
    private val baseUrl: String = RaylApi.BASE_URL,
    client: () -> HttpClient = ::createHttpClient
) : HubSwitchesApi {
    private val client by lazy(client)

    override suspend fun disabledWidgets(): Set<String>? = try {
        val response = client.get(baseUrl + RaylApi.HUB_WIDGETS_PATH)
        if (response.status.isSuccess()) {
            json.decodeFromString(HubWidgetSwitches.serializer(), response.bodyAsText())
                .disabled.toSet()
        } else {
            null
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The widget definitions the developer turned off remotely, by definition id. What the backend
 * last said is kept, so a widget stays off offline and after a restart; before it ever answered,
 * nothing is off. It is asked at most every [intervalMs], whenever the Hub refreshes.
 */
class HubWidgetKillSwitch(
    private val api: HubSwitchesApi,
    private val settings: Settings,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS
) {
    private val mutableDisabled = MutableStateFlow(
        settings.getStringOrNull(KEY)?.split(',')?.filter(String::isNotBlank)?.toSet().orEmpty()
    )
    val disabled: StateFlow<Set<String>> = mutableDisabled.asStateFlow()
    private var askedAtMs: Long? = null

    suspend fun refresh(nowMs: Long) {
        val last = askedAtMs
        if (last != null && nowMs - last in 0 until intervalMs) return
        askedAtMs = nowMs
        val answer = api.disabledWidgets() ?: return
        settings.putString(KEY, answer.sorted().joinToString(","))
        mutableDisabled.value = answer
    }

    companion object {
        /** Android keeps it in its own preferences file; iOS in the app's defaults, under [KEY]. */
        const val STORAGE_NAME = "rayl.hub.switches"
        const val KEY = "paymentHub.disabledWidgets"
        const val DEFAULT_INTERVAL_MS = 5 * 60_000L
    }
}
