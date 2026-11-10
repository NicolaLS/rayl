package xyz.lilsus.raylsuite.core.telemetry

import com.russhwolf.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import xyz.lilsus.raylsuite.core.raylapi.TelemetryEvent

/** What happens to recorded events. */
enum class TelemetryMode {
    /** Automated builds (E2E, benchmark): nothing is kept. */
    Off,

    /** Debug builds: each event is logged under `RaylTelemetry` and nothing is sent. */
    Log,

    /** Release builds: events are sent to the Rayl backend. */
    Upload
}

/**
 * Buffers events in memory and hands them to [send] off the caller's thread. Nothing is written
 * to disk except the user's choice and the once-per-installation flags, and nothing is recorded
 * while the user has turned usage data off.
 *
 * [send] returns false when the batch should be tried again. [newInstallation] is asked once, on
 * the first start with usage data: only installations that begin after it report onboarding
 * steps, so people who updated don't show up as a funnel that starts in the middle.
 */
class TelemetryEngine(
    private val settings: Settings,
    private val mode: TelemetryMode,
    scope: CoroutineScope,
    newInstallation: () -> Boolean,
    private val log: (String) -> Unit = {},
    private val send: suspend (List<TelemetryEvent>) -> Boolean
) {
    private val mutableEnabled = MutableStateFlow(settings.getBoolean(ENABLED_KEY, true))
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    private val reportsOnboarding: Boolean =
        settings.getBooleanOrNull(ONBOARDING_KEY)
            ?: newInstallation().also { settings.putBoolean(ONBOARDING_KEY, it) }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val buffer = ArrayDeque<TelemetryEvent>()
    private var delayedFlush: Job? = null

    init {
        scope.launch {
            for (command in commands) {
                when (command) {
                    is Command.Record -> record(command.event, command.onceKey)

                    is Command.Flush -> {
                        upload()
                        command.done?.complete(Unit)
                    }

                    is Command.FlushAfter -> {
                        delayedFlush?.cancel()
                        delayedFlush = launch {
                            delay(command.delayMs)
                            commands.trySend(Command.Flush())
                        }
                    }

                    Command.Clear -> buffer.clear()

                    is Command.SendAlone -> if (enabled.value) {
                        if (mode == TelemetryMode.Log) {
                            log(Json.encodeToString(TelemetryEvent.serializer(), command.event))
                        } else {
                            send(listOf(command.event))
                        }
                    }
                }
            }
        }
    }

    /** Turning usage data off also discards what has not been sent yet. */
    fun setEnabled(enabled: Boolean) {
        settings.putBoolean(ENABLED_KEY, enabled)
        mutableEnabled.value = enabled
        if (!enabled) commands.trySend(Command.Clear)
    }

    /** Safe from any thread; returns at once. */
    fun record(event: TelemetryEvent) {
        if (mode != TelemetryMode.Off && enabled.value) commands.trySend(Command.Record(event))
    }

    /** Records [event] the first time [key] is seen on this installation, and never again. */
    fun recordOnce(key: String, event: TelemetryEvent) {
        if (mode != TelemetryMode.Off && enabled.value) {
            commands.trySend(Command.Record(event, onceKey = key))
        }
    }

    /**
     * Sends [event] at once and on its own, without what is buffered. For the one event that must
     * arrive before the user may turn usage data off: that they were asked. If it cannot be sent
     * now it is dropped, never kept for later.
     */
    fun sendNow(event: TelemetryEvent) {
        if (mode != TelemetryMode.Off && enabled.value) commands.trySend(Command.SendAlone(event))
    }

    fun recordOnboarding(key: String, event: TelemetryEvent) {
        if (reportsOnboarding) recordOnce(key, event)
    }

    fun flush() {
        commands.trySend(Command.Flush())
    }

    /** Sends what is buffered and returns once the backend has answered or failed. */
    suspend fun flushNow() {
        val done = CompletableDeferred<Unit>()
        commands.trySend(Command.Flush(done))
        done.await()
    }

    /** Sends after [delayMs], so an upload never competes with what the user is waiting for. */
    fun flushAfter(delayMs: Long) {
        commands.trySend(Command.FlushAfter(delayMs))
    }

    private fun record(event: TelemetryEvent, onceKey: String?) {
        // The user may have turned usage data off while the event was queued.
        if (!enabled.value) return
        if (onceKey != null) {
            val flag = ONCE_KEY_PREFIX + onceKey
            if (settings.getBoolean(flag, false)) return
            settings.putBoolean(flag, true)
        }
        if (mode == TelemetryMode.Log) {
            log(Json.encodeToString(TelemetryEvent.serializer(), event))
            return
        }
        if (buffer.size == MAX_BUFFERED_EVENTS) buffer.removeFirst()
        buffer.addLast(event)
    }

    private suspend fun upload() {
        if (buffer.isEmpty() || !enabled.value) return
        val batch = buffer.toList()
        buffer.clear()
        if (send(batch) || !enabled.value) return
        // Events recorded during the request stay behind the batch; the oldest give way.
        buffer.addAll(0, batch)
        while (buffer.size > MAX_BUFFERED_EVENTS) buffer.removeFirst()
    }

    private sealed interface Command {
        data class Record(val event: TelemetryEvent, val onceKey: String? = null) : Command

        class Flush(val done: CompletableDeferred<Unit>? = null) : Command

        data class FlushAfter(val delayMs: Long) : Command

        data object Clear : Command

        data class SendAlone(val event: TelemetryEvent) : Command
    }

    companion object {
        /** One process never needs more; it also bounds memory while the backend is away. */
        const val MAX_BUFFERED_EVENTS = 100

        // Stored in the app's preferences from the release after 1.1.
        private const val ENABLED_KEY = "rayl.usage_data.enabled"
        private const val ONBOARDING_KEY = "rayl.usage_data.onboarding"
        private const val ONCE_KEY_PREFIX = "rayl.usage_data.once."
    }
}
