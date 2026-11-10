package xyz.lilsus.raylsuite.core.camera

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import xyz.lilsus.raylsuite.core.raylapi.TelemetryCatalog
import xyz.lilsus.raylsuite.core.raylapi.TelemetryScanDecoder
import xyz.lilsus.raylsuite.core.raylapi.TelemetryScanRunnerUp
import xyz.lilsus.raylsuite.core.raylapi.TelemetryScanSession
import xyz.lilsus.raylsuite.core.raylapi.TelemetryValue
import xyz.lilsus.raylsuite.core.raylapi.telemetryEvent
import xyz.lilsus.raylsuite.core.telemetry.RaylTrace
import xyz.lilsus.raylsuite.core.telemetry.Telemetry
import xyz.lilsus.raylsuite.core.telemetry.TelemetryRecorder
import xyz.lilsus.raylsuite.core.telemetry.TracePoint

/**
 * Times one scanner from start to its first decoded code and reports that as one `scan` event.
 * It never sees what the code contains. Calls arrive from the main thread and the camera's
 * analysis thread; a lost update only costs one event its measure.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ScanTelemetry(
    private val variant: () -> CameraVariant = CameraVariant::current,
    private val recorder: TelemetryRecorder = Telemetry,
    private val nowMs: () -> Long = RaylTrace::nowMs,
    private val countsFromLaunch: Boolean = RaylTrace.countsFromLaunch,
    private val firstSessionOfProcess: () -> Boolean = { firstSession.compareAndSet(true, false) }
) {
    @Volatile
    private var session: Session? = null

    fun sessionStarted() {
        sessionEnded()
        session = Session(first = firstSessionOfProcess(), startedMs = nowMs())
    }

    /** The camera accepted its configuration. */
    fun ready() {
        session?.run { if (readyMs == null) readyMs = nowMs() }
    }

    fun firstFrame() {
        session?.run {
            if (firstFrameMs == null) {
                firstFrameMs = nowMs()
                RaylTrace.mark(TracePoint.CameraFirstFrame)
            }
        }
    }

    /** One analysed camera frame of [width] x [height] pixels. Android only. */
    fun frameAnalyzed(durationMs: Long, width: Int, height: Int) {
        session?.run {
            frames += 1
            analysisMs += durationMs
            frameWidth = width
            frameHeight = height
        }
    }

    /** The capture format in use, where frames are not analysed by Rayl's code (iOS). */
    fun frameSize(width: Int, height: Int) {
        session?.run {
            frameWidth = width
            frameHeight = height
        }
    }

    /**
     * [decoder] decoded a code. Only a session's first one is reported: at once, or when
     * [racing] another reader, once that one has decoded it too or the session ends.
     */
    fun qrDetected(decoder: TelemetryScanDecoder, racing: Boolean = false) {
        RaylTrace.mark(TracePoint.QrDetected)
        val session = session ?: return
        if (session.reported) return
        session.reported = true
        val now = nowMs()
        val event = PendingScan(
            detectedMs = now,
            dimensions = mapOf(
                TelemetryCatalog.CAMERA_VARIANT to variant().telemetry,
                "session" to
                    if (session.first) {
                        TelemetryScanSession.First
                    } else {
                        TelemetryScanSession.Restart
                    },
                "decoder" to decoder
            ),
            measures = mapOf(
                "camera_start_to_ready_ms" to session.readyMs?.minus(session.startedMs),
                "camera_start_to_first_frame_ms" to
                    session.firstFrameMs?.minus(session.startedMs),
                "first_frame_to_qr_ms" to session.firstFrameMs?.let { now - it },
                "frames_analyzed" to session.frames.takeIf { it > 0 },
                "frame_analysis_avg_ms" to
                    session.frames.takeIf { it > 0 }?.let { session.analysisMs / it },
                "frame_width" to session.frameWidth?.toLong(),
                "frame_height" to session.frameHeight?.toLong(),
                "process_to_qr_ms" to now.takeIf { session.first && countsFromLaunch }
            )
        )
        if (racing) session.pending = event else record(event)
    }

    /** The slower reader decoded the reported code too, [lagMs] after the first. */
    fun runnerUpDetected(lagMs: Long) {
        val pending = takePending() ?: return
        record(
            pending,
            dimensions = mapOf("runner_up" to TelemetryScanRunnerUp.Decoded),
            measures = mapOf("runner_up_lag_ms" to lagMs)
        )
    }

    /** The scanner stopped. A race the slower reader did not finish is reported as missed. */
    fun sessionEnded() {
        val pending = takePending() ?: return
        record(
            pending,
            dimensions = mapOf("runner_up" to TelemetryScanRunnerUp.Missed),
            measures = mapOf("runner_up_wait_ms" to nowMs() - pending.detectedMs)
        )
    }

    private fun takePending(): PendingScan? = session?.run { pending.also { pending = null } }

    private fun record(
        scan: PendingScan,
        dimensions: Map<String, TelemetryValue> = emptyMap(),
        measures: Map<String, Long?> = emptyMap()
    ) {
        recorder.record(
            telemetryEvent(
                TelemetryCatalog.scan,
                dimensions = scan.dimensions + dimensions,
                measures = scan.measures + measures
            )
        )
    }

    private class PendingScan(
        val detectedMs: Long,
        val dimensions: Map<String, TelemetryValue>,
        val measures: Map<String, Long?>
    )

    private class Session(val first: Boolean, val startedMs: Long) {
        @Volatile var readyMs: Long? = null

        @Volatile var firstFrameMs: Long? = null

        @Volatile var frames = 0L

        @Volatile var analysisMs = 0L

        @Volatile var frameWidth: Int? = null

        @Volatile var frameHeight: Int? = null

        @Volatile var reported = false

        @Volatile var pending: PendingScan? = null
    }

    private companion object {
        val firstSession = AtomicBoolean(true)
    }
}
