package xyz.lilsus.raylsuite.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShortExposureFrameRateTest {
    @Test
    fun aFixedRateBeatsAVariableOneThatMayDropLower() {
        // A typical phone: the default, 15..30, may expose for 1/15 s in a dim room.
        val ranges = listOf(15..15, 15..30, 7..30, 30..30)

        assertEquals(30..30, pickShortExposureFrameRate(ranges, maxFps = 60))
    }

    @Test
    fun theFastestFixedRateWithinTheLimitWins() {
        val ranges = listOf(15..30, 30..30, 60..60, 120..120)

        assertEquals(60..60, pickShortExposureFrameRate(ranges, maxFps = 60))
        assertEquals(30..30, pickShortExposureFrameRate(ranges, maxFps = 30))
    }

    @Test
    fun withTheSameSlowestRateTheOneThatCanGoFasterWins() {
        assertEquals(30..60, pickShortExposureFrameRate(listOf(30..30, 30..60), maxFps = 60))
    }

    @Test
    fun nothingIsPickedWhenNoRangeFits() {
        assertNull(pickShortExposureFrameRate(emptyList(), maxFps = 60))
        // Old camera drivers report frames per 1000 seconds.
        assertNull(pickShortExposureFrameRate(listOf(15_000..30_000), maxFps = 60))
    }
}
