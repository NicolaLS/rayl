package xyz.lilsus.raylsuite.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import xyz.lilsus.raylsuite.core.raylapi.TelemetryEvent

class ScanTelemetryTest {
    private var now = 0L
    private var sessions = 0
    private val recorded = mutableListOf<TelemetryEvent>()

    private fun telemetry(countsFromLaunch: Boolean = true) = ScanTelemetry(
        variant = { CameraVariant.Control },
        recorder = { recorded += it },
        nowMs = { now },
        countsFromLaunch = countsFromLaunch,
        firstSessionOfProcess = { sessions++ == 0 }
    )

    @Test
    fun reportsASessionsFirstCodeWithItsStageDurations() {
        val telemetry = telemetry()
        now = 300
        telemetry.sessionStarted()
        now = 420
        telemetry.ready()
        now = 650
        telemetry.firstFrame()
        telemetry.frameAnalyzed(durationMs = 30, width = 1920, height = 1440)
        telemetry.frameAnalyzed(durationMs = 50, width = 1920, height = 1440)
        now = 2_150

        telemetry.qrDetected()
        // The same code still in view, or the next one, is not another scan.
        now = 9_000
        telemetry.qrDetected()

        assertEquals(
            listOf(
                TelemetryEvent(
                    name = "scan",
                    dimensions = mapOf("camera_variant" to "control", "session" to "first"),
                    measures = mapOf(
                        "camera_start_to_ready_ms" to 120L,
                        "camera_start_to_first_frame_ms" to 350L,
                        "first_frame_to_qr_ms" to 1_500L,
                        "frames_analyzed" to 2L,
                        "frame_analysis_avg_ms" to 40L,
                        "frame_width" to 1920L,
                        "frame_height" to 1440L,
                        "process_to_qr_ms" to 2_150L
                    )
                )
            ),
            recorded
        )
    }

    @Test
    fun aRestartedScannerDoesNotCountFromProcessStart() {
        val telemetry = telemetry()
        telemetry.sessionStarted()
        telemetry.qrDetected()
        now = 60_000
        telemetry.sessionStarted()
        now = 60_200
        telemetry.firstFrame()
        now = 60_900

        telemetry.qrDetected()

        assertEquals(mapOf("camera_variant" to "control", "session" to "restart"), recorded.last().dimensions)
        // Without Rayl's own frame analysis (iOS) there is nothing to say about frames.
        assertEquals(
            mapOf("camera_start_to_first_frame_ms" to 200L, "first_frame_to_qr_ms" to 700L),
            recorded.last().measures
        )
    }

    @Test
    fun aPrewarmedProcessDoesNotCountFromProcessStart() {
        val telemetry = telemetry(countsFromLaunch = false)
        telemetry.sessionStarted()
        now = 500

        telemetry.qrDetected()

        assertEquals(emptyMap(), recorded.single().measures)
    }

    @Test
    fun aRollPicksTheVariantWhoseShareContainsIt() {
        // An off-by-one here would silently change how many launches try a configuration.
        val options = listOf("control" to 90, "candidate" to 10)

        assertEquals("control", pickWeighted(options, roll = 0))
        assertEquals("control", pickWeighted(options, roll = 89))
        assertEquals("candidate", pickWeighted(options, roll = 90))
        assertEquals("candidate", pickWeighted(options, roll = 99))
        assertFailsWith<IllegalStateException> { pickWeighted(options, roll = 100) }
    }
}
