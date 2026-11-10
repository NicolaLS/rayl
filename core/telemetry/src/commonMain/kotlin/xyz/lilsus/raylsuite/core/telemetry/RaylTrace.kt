package xyz.lilsus.raylsuite.core.telemetry

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLongArray
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource

/** A moment on the payment path. The first and the latest time of each are kept in memory. */
enum class TracePoint(val traceName: String) {
    /** The scanner's first camera frame; on iOS, the capture session running. */
    CameraFirstFrame("camera.first_frame"),
    QrDetected("camera.qr_detected"),
    CameraRestart("camera.restart"),

    /** Rayl's wallet can pay. */
    WalletReady("wallet.ready"),
    PaymentAdmitted("payment.admitted"),
    PaymentPrepared("payment.prepared"),
    PaymentSubmitted("payment.submitted"),
    PaymentOutcome("payment.outcome")
}

/** A stretch of work that shows up as a section in Perfetto and Instruments. */
enum class TraceSpan(val traceName: String) {
    CameraStartToReady("camera.start_to_ready"),
    CameraStartToFirstFrame("camera.start_to_first_frame"),
    CameraConfigureSession("camera.configure_session"),
    CameraFrameAnalysis("camera.frame_analysis"),
    CameraStop("camera.stop"),
    PaymentPrepare("payment.prepare"),
    PaymentSettle("payment.settle")
}

interface TraceInterval {
    /** Ends the section; ending it again does nothing. */
    fun end()
}

/**
 * The one place the payment path is timed. Each mark feeds three readers: platform traces
 * (`androidx.tracing`, `os_signpost`) for benchmarks and profilers, the `RaylTiming` debug log,
 * and the in-memory timeline that usage data takes its stage durations from.
 */
@OptIn(ExperimentalAtomicApi::class)
object RaylTrace {
    private val start = TimeSource.Monotonic.markNow()
    private val offsetMs = processAgeMs()
    private val first = AtomicLongArray(TracePoint.entries.size) { UNSET }
    private val latest = AtomicLongArray(TracePoint.entries.size) { UNSET }

    /** Debug builds: log marks under the `RaylTiming` tag. Set first at launch. */
    @Volatile
    var debugLog = false

    /**
     * False when the process was started before the user opened the app (iOS prewarming), so
     * durations counted from process start mean nothing.
     */
    val countsFromLaunch: Boolean = offsetMs != null

    /** Milliseconds since the process started; on iOS, since the app initialised. */
    fun nowMs(): Long = (offsetMs ?: 0L) + start.elapsedNow().inWholeMilliseconds

    fun mark(point: TracePoint): Long {
        val now = nowMs()
        first.compareAndSetAt(point.ordinal, UNSET, now)
        latest.storeAt(point.ordinal, now)
        platformTraceEvent(point.traceName)
        if (debugLog) platformLog(TIMING_TAG, "$now ms ${point.traceName}")
        return now
    }

    fun firstMs(point: TracePoint): Long? = first.loadAt(point.ordinal).takeIf { it != UNSET }

    fun latestMs(point: TracePoint): Long? = latest.loadAt(point.ordinal).takeIf { it != UNSET }

    /** Null while no trace is being recorded, so the hot path pays nothing for it. */
    fun begin(span: TraceSpan): TraceInterval? = platformTraceBegin(span.traceName)

    /**
     * A debug-log line with free text, such as an SDK event or an error. It never leaves the
     * device: usage data has no free text.
     */
    fun debug(message: String) {
        if (debugLog) platformLog(TIMING_TAG, "${nowMs()} ms $message")
    }

    private const val UNSET = -1L
    private const val TIMING_TAG = "RaylTiming"
}

/** How long the process had been running when it loaded this code, or null if that is unusable. */
internal expect fun processAgeMs(): Long?

internal expect fun platformTraceEvent(name: String)

internal expect fun platformTraceBegin(name: String): TraceInterval?

internal expect fun platformLog(tag: String, message: String)
