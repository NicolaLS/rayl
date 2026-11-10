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
    private var winner: Emission? = null

    fun reset() {
        gates.clear()
        winner = null
    }

    /**
     * What [decoder] saw at [atMs]: a code's value, or null for none. Returns the value when it
     * should be handed on.
     */
    fun observe(decoder: TelemetryScanDecoder, value: String?, atMs: Long): String? {
        val emitted = gates.getOrPut(decoder) { QrPresenceGate() }.observe(value) ?: return null
        val first = winner
        if (first != null &&
            first.decoder != decoder &&
            first.value == emitted &&
            atMs - first.atMs < SAME_CODE_WINDOW_MS
        ) {
            telemetry.runnerUpDetected(lagMs = atMs - first.atMs)
            return null
        }
        winner = Emission(emitted, decoder, atMs)
        telemetry.qrDetected(decoder, racing = true)
        return emitted
    }

    private class Emission(val value: String, val decoder: TelemetryScanDecoder, val atMs: Long)

    private companion object {
        /** Later than this, the same code from the other reader is a new scan, not the runner-up. */
        const val SAME_CODE_WINDOW_MS = 10_000L
    }
}
