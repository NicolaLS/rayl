package xyz.lilsus.raylsuite.core.camera

/**
 * The frame rate range that keeps a moving camera's frames sharpest. Android's auto-exposure has
 * no limit of its own on exposure time; it only never exposes a frame for longer than the slowest
 * frame rate it is allowed. So the range with the highest lower bound has the shortest worst-case
 * exposure. Among those, the highest upper bound gives the most frames in good light.
 *
 * Ranges faster than [maxFps] are left out. Null when none is left.
 */
internal fun pickShortExposureFrameRate(ranges: Collection<IntRange>, maxFps: Int): IntRange? =
    ranges
        .filter { it.first > 0 && it.last <= maxFps }
        .maxWithOrNull(compareBy<IntRange>({ it.first }, { it.last }))
