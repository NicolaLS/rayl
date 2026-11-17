package xyz.lilsus.raylsuite.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.lilsus.raylsuite.core.raylapi.TelemetryEvent
import xyz.lilsus.raylsuite.core.raylapi.TelemetryScanDecoder.AvFoundation
import xyz.lilsus.raylsuite.core.raylapi.TelemetryScanDecoder.Vision

class DecoderRaceTest {
    private var now = 0L
    private val recorded = mutableListOf<TelemetryEvent>()
    private val telemetry = ScanTelemetry(
        variant = { CameraVariant.Control },
        recorder = { recorded += it },
        nowMs = { now },
        countsFromLaunch = false,
        firstSessionOfProcess = { true },
        afterScan = {}
    ).also { it.sessionStarted() }
    private val race = DecoderRace(telemetry)
    private val nothing = emptyList<String>()

    private fun at(ms: Long): Long {
        now = ms
        return ms
    }

    @Test
    fun theFirstReaderHandsTheCodeOnAndTheOtherIsOnlyTimed() {
        assertEquals(nothing, race.observe(AvFoundation, nothing, at(900)))

        assertEquals(listOf("lnbc1"), race.observe(Vision, listOf("lnbc1"), at(1_000)))
        // Nothing is reported until the race is decided.
        assertEquals(emptyList(), recorded)
        // Handing it on again would start the payment twice.
        assertEquals(nothing, race.observe(AvFoundation, listOf("lnbc1"), at(1_040)))

        assertEquals("vision", recorded.single().dimensions["decoder"])
        assertEquals("decoded", recorded.single().dimensions["runner_up"])
        assertEquals(40L, recorded.single().measures["runner_up_lag_ms"])
    }

    @Test
    fun aReaderThatNeverDecodesTheCodeIsReportedAsMissedWithHowLongItHad() {
        assertEquals(listOf("lnbc1"), race.observe(AvFoundation, listOf("lnbc1"), at(1_000)))
        assertEquals(nothing, race.observe(Vision, nothing, at(1_100)))

        now = 1_700
        telemetry.sessionEnded()
        // Stopping twice, or starting again, reports nothing more.
        telemetry.sessionEnded()
        telemetry.sessionStarted()

        assertEquals("avfoundation", recorded.single().dimensions["decoder"])
        assertEquals("missed", recorded.single().dimensions["runner_up"])
        assertEquals(700L, recorded.single().measures["runner_up_wait_ms"])
        assertNull(recorded.single().measures["runner_up_lag_ms"])
    }

    @Test
    fun anotherCodeOrTheSameCodeMuchLaterIsAScanOfItsOwn() {
        assertEquals(listOf("lnbc1"), race.observe(Vision, listOf("lnbc1"), at(1_000)))

        // A different code in view of the other reader is not the runner-up of the first.
        assertEquals(listOf("lnbc2"), race.observe(AvFoundation, listOf("lnbc2"), at(1_050)))
        // Long after the first reader, the same code is a new scan, not a late runner-up.
        assertEquals(listOf("lnbc1"), race.observe(AvFoundation, listOf("lnbc1"), at(20_000)))
    }

    @Test
    fun aReaderThatLostTheCodeAndFindsItAgainHandsItOnLikeASingleReaderWould() {
        assertEquals(listOf("lnbc1"), race.observe(Vision, listOf("lnbc1"), at(1_000)))
        assertEquals(nothing, race.observe(Vision, listOf("lnbc1"), at(1_030)))
        repeat(3) { assertEquals(nothing, race.observe(Vision, nothing, at(1_100))) }

        assertEquals(listOf("lnbc1"), race.observe(Vision, listOf("lnbc1"), at(1_500)))
    }

    @Test
    fun onceTheRunnerUpIsTimedTheCodeIsANewScanForWhoeverFindsItNext() {
        assertEquals(listOf("menu"), race.observe(AvFoundation, listOf("menu"), at(1_000)))
        assertEquals(nothing, race.observe(Vision, listOf("menu"), at(1_040)))
        // Both lose the code; Vision, the runner-up of before, is the first to find it again.
        repeat(3) {
            race.observe(AvFoundation, nothing, at(2_000))
            race.observe(Vision, nothing, at(2_000))
        }

        assertEquals(listOf("menu"), race.observe(Vision, listOf("menu"), at(3_000)))
        assertEquals(nothing, race.observe(AvFoundation, listOf("menu"), at(3_050)))
    }

    @Test
    fun aRestartedScannerStartsANewRace() {
        assertEquals(listOf("lnbc1"), race.observe(Vision, listOf("lnbc1"), at(1_000)))

        race.reset()

        // Not the runner-up of a race from before the restart.
        assertEquals(listOf("lnbc1"), race.observe(AvFoundation, listOf("lnbc1"), at(1_200)))
    }
}
