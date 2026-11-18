package xyz.lilsus.rayl.tools.regtest.bank

import breez_sdk_spark.AutoOptimizationEvent
import breez_sdk_spark.BreezSdk
import breez_sdk_spark.EventListener
import breez_sdk_spark.SdkEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Follows Breez's automatic reorganization of a wallet's funds (leaf optimization), which runs
 * after every payment. While it runs, another session on the same wallet sees the balance short
 * by the pieces being swapped, for minutes if the session doing it goes away (MOB-114). A test
 * that hands a wallet to a new session waits for it to end first, as an app would that stays open
 * a few seconds after a payment.
 */
class LeafOptimizationWatch {
    private data class State(val running: Boolean, val ended: Int)

    private val state = MutableStateFlow(State(running = false, ended = 0))

    fun started() = state.update { it.copy(running = true) }

    fun ended() = state.update { State(running = false, ended = it.ended + 1) }

    /** Where things stand now; pass it to [awaitEndedSince] after the payment. */
    fun mark(): Int = state.value.ended

    /** Waits until a reorganization ended after [mark] and none is running. */
    suspend fun awaitEndedSince(mark: Int) {
        withTimeoutOrNull(WAIT_MILLIS) { state.first { !it.running && it.ended > mark } }
            ?: error("Breez's leaf optimization didn't end within ${WAIT_MILLIS / 1_000} s")
    }

    /** Feeds this watch from [event], if it is about the optimization. */
    fun on(event: AutoOptimizationEvent) = when (event) {
        is AutoOptimizationEvent.Started -> started()

        is AutoOptimizationEvent.RoundCompleted -> Unit

        is AutoOptimizationEvent.Completed,
        is AutoOptimizationEvent.Skipped,
        is AutoOptimizationEvent.Failed,
        is AutoOptimizationEvent.Cancelled -> ended()
    }

    private companion object {
        const val WAIT_MILLIS = 60_000L
    }
}

/** Runs [block] with a [LeafOptimizationWatch] fed by this session's events. */
suspend fun <T> BreezSdk.watchingLeafOptimization(block: suspend (LeafOptimizationWatch) -> T): T {
    val watch = LeafOptimizationWatch()
    val listener = addEventListener(
        object : EventListener {
            override suspend fun onEvent(event: SdkEvent) {
                if (event is SdkEvent.AutoOptimization) watch.on(event.optimizationEvent)
            }
        }
    )
    return try {
        block(watch)
    } finally {
        removeEventListener(listener)
    }
}
