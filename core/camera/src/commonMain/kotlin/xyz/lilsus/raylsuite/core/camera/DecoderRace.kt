package xyz.lilsus.raylsuite.core.camera

import xyz.lilsus.raylsuite.core.raylapi.TelemetryScanDecoder

/**
 * Two code readers watching the same camera, to learn which one decodes first. The first to read
 * a code hands it on; the other reading the same code shortly after is the runner-up and is only
 * timed, so a code is never handed on twice. Each reader keeps its own [QrPresenceGate], because
 * each loses and finds the code on its own.
 *
 * Not thread-safe: feed it from one queue.
 */
internal class DecoderRace(private val telemetry: ScanTelemetry) {
    private val gates = mutableMapOf<TelemetryScanDecoder, QrPresenceGate>()
    private val firstReads = mutableMapOf<String, FirstRead>()

    fun reset() {
        gates.clear()
        firstReads.clear()
    }

    /**
     * What [decoder] read at [atMs]: the codes in its frame, most prominent first. Returns the
     * ones to hand on, in that order.
     */
    fun observe(decoder: TelemetryScanDecoder, values: List<String>, atMs: Long): List<String> =
        observe(decoder, values, atMs, handOn = true)

    /**
     * The same for a scanner that has stopped and hands nothing on any more: [decoder] can only
     * still finish as the runner-up.
     */
    fun observeRunnerUp(decoder: TelemetryScanDecoder, values: List<String>, atMs: Long) {
        observe(decoder, values, atMs, handOn = false)
    }

    private fun observe(
        decoder: TelemetryScanDecoder,
        values: List<String>,
        atMs: Long,
        handOn: Boolean
    ): List<String> {
        val arrived = gates.getOrPut(decoder) { QrPresenceGate() }.observe(values)
        val handedOn = arrived.filter { value ->
            val first = firstReads[value]
            if (first != null && first.awaitsRunnerUp(decoder, atMs)) {
                // Once: the next time either reader finds this code, it is a new scan.
                first.runnerUpSeen = true
                telemetry.runnerUpDetected(lagMs = atMs - first.atMs)
                false
            } else {
                if (handOn) firstReads[value] = FirstRead(decoder, atMs)
                handOn
            }
        }
        if (handedOn.isNotEmpty()) telemetry.qrDetected(decoder, racing = true)
        return handedOn
    }

    private class FirstRead(val decoder: TelemetryScanDecoder, val atMs: Long) {
        var runnerUpSeen = false

        fun awaitsRunnerUp(other: TelemetryScanDecoder, atMs: Long): Boolean =
            !runnerUpSeen && other != decoder && atMs - this.atMs < SAME_CODE_WINDOW_MS
    }

    private companion object {
        /** Later than this, the same code from the other reader is a new scan, not the runner-up. */
        const val SAME_CODE_WINDOW_MS = 10_000L
    }
}
