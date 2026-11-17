package xyz.lilsus.raylsuite.core.camera

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow

/**
 * How the scanner lets the camera's auto-exposure expose a frame. A frame's light is its exposure
 * time times its gain (ISO), and the camera aims every scene at the same brightness, however dark
 * the room. That costs the scanner twice in a dark room: a long exposure blurs a moving code, and
 * high gain is noise, which a code cannot be read through from far away. And it buys nothing: a
 * code in a dark room is nearly always on a screen, which is as bright there as anywhere, and the
 * camera's brightening only burns it out.
 *
 * So a frame gets at most the light of [longestMs] at [isoCeiling], and a room darker than that
 * stays dark in the picture. Within it the exposure is as short as [isoCeiling] allows, down to
 * [shortestMs].
 */
internal class ExposurePolicy(
    val shortestMs: Double,
    val longestMs: Double,
    val isoCeiling: Double
) {
    init {
        require(shortestMs > 0 && longestMs >= shortestMs && isoCeiling > 0)
    }

    /**
     * What the camera is told: expose a frame for at most [limitMs], and aim [biasEv] stops away
     * from its usual brightness (negative is darker).
     */
    class Setting(val limitMs: Double, val biasEv: Double)

    /** Before the camera has reported anything. */
    val start = Setting(limitMs = longestMs, biasEv = 0.0)

    /**
     * The setting for what the camera reports under [current]: it exposes [exposureMs] at [iso],
     * and its frame is [evFromTarget] stops away from the brightness it aims for (negative is too
     * dark). [current] is returned itself while nothing would change by much, so the setting
     * does not flutter.
     */
    fun settingFor(
        current: Setting,
        exposureMs: Double,
        iso: Double,
        evFromTarget: Double
    ): Setting {
        // The light that would bring this scene to the camera's usual brightness.
        val sceneLight = exposureMs * iso / 2.0.pow(current.biasEv + evFromTarget)
        if (!sceneLight.isFinite() || sceneLight <= 0) return current
        val light = min(sceneLight, longestMs * isoCeiling)
        val limitMs = (light / isoCeiling).coerceIn(shortestMs, longestMs)
        val biasEv = log2(light / sceneLight)
        val limitSteady = abs(limitMs - current.limitMs) < current.limitMs * STEADY_LIMIT_SHARE
        val biasSteady = abs(biasEv - current.biasEv) < STEADY_BIAS_EV
        if (limitSteady && biasSteady) return current
        return Setting(
            limitMs = if (limitSteady) current.limitMs else limitMs,
            biasEv = if (biasSteady) current.biasEv else biasEv
        )
    }

    private companion object {
        const val STEADY_LIMIT_SHARE = 0.2
        const val STEADY_BIAS_EV = 0.25
    }
}
