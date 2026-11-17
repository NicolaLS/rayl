package xyz.lilsus.raylsuite.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ExposurePolicyTest {
    private val policy = ExposurePolicy(shortestMs = 4.0, longestMs = 8.0, isoCeiling = 1000.0)

    @Test
    fun aDarkRoomStaysDarkInsteadOfBlurredOrNoisy() {
        // Measured on an iPhone 17 in a dark room: left alone the camera used 33 ms at ISO 2000.
        val setting = policy.settingFor(policy.start, exposureMs = 33.3, iso = 2000.0, evFromTarget = 0.0)

        assertEquals(8.0, setting.limitMs)
        // 8 ms at ISO 1000 is 8000 of the 66600 the camera wanted: three stops darker.
        assertEquals(-3.06, setting.biasEv, absoluteTolerance = 0.01)
    }

    @Test
    fun theDarkRoomIsReadTheSameOnceTheSettingIsInUse() {
        val dark = policy.settingFor(policy.start, exposureMs = 33.3, iso = 2000.0, evFromTarget = 0.0)

        // The camera now exposes 8 ms at ISO 1000 and is on its darker target.
        val again = policy.settingFor(dark, exposureMs = 8.0, iso = 1000.0, evFromTarget = 0.0)

        assertSame(dark, again)
    }

    @Test
    fun goodLightGetsTheShortestExposureAndItsUsualBrightness() {
        // Bright enough for 8 ms at ISO 400: 4 ms needs ISO 800, which is under the ceiling.
        val setting = policy.settingFor(policy.start, exposureMs = 8.0, iso = 400.0, evFromTarget = 0.0)

        assertEquals(4.0, setting.limitMs)
        assertEquals(0.0, setting.biasEv)
    }

    @Test
    fun inBetweenTheGainStopsAtTheCeiling() {
        val setting = policy.settingFor(policy.start, exposureMs = 8.0, iso = 750.0, evFromTarget = 0.0)

        assertEquals(6.0, setting.limitMs)
        assertEquals(0.0, setting.biasEv)
    }

    @Test
    fun whenTheLightComesBackTheDarkerTargetIsDropped() {
        val dark = ExposurePolicy.Setting(limitMs = 8.0, biasEv = -3.0)

        // Three stops under its usual brightness the camera now needs only 4 ms at ISO 100.
        val setting = policy.settingFor(dark, exposureMs = 4.0, iso = 100.0, evFromTarget = 0.0)

        assertEquals(4.0, setting.limitMs)
        assertEquals(0.0, setting.biasEv)
    }

    @Test
    fun aSmallChangeInLightKeepsTheSetting() {
        val current = ExposurePolicy.Setting(limitMs = 6.0, biasEv = 0.0)

        assertSame(current, policy.settingFor(current, exposureMs = 6.0, iso = 1100.0, evFromTarget = 0.0))
    }

    @Test
    fun aReadingThatMakesNoSenseKeepsTheSetting() {
        val current = policy.start

        assertSame(current, policy.settingFor(current, exposureMs = 0.0, iso = 500.0, evFromTarget = 0.0))
        assertSame(
            current,
            policy.settingFor(current, exposureMs = Double.NaN, iso = 500.0, evFromTarget = 0.0)
        )
    }
}
