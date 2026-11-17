package xyz.lilsus.raylsuite.core.camera

import kotlin.math.abs

/** A code read in one frame and where it was, in the frame's own units. */
internal data class QrDetectionCandidate(
    val value: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * The values of one frame's codes, most prominent first: the larger code, and of two the same
 * size the one nearer the centre. All of them are handed on, because only whoever the scanner
 * works for knows which one it wants: the invoice, not the larger menu link beside it.
 */
internal fun rankQrValues(
    candidates: List<QrDetectionCandidate>,
    frameWidth: Float,
    frameHeight: Float
): List<String> {
    if (candidates.isEmpty()) return emptyList()
    if (candidates.size == 1) return listOfNotNull(candidates[0].value.takeIf { it.isNotBlank() })

    val width = frameWidth.takeIf { it > 0f } ?: 1f
    val height = frameHeight.takeIf { it > 0f } ?: 1f
    return candidates
        .filter { it.value.isNotBlank() }
        .map { candidate ->
            val dx = (candidate.left + candidate.right) * 0.5f / width - 0.5f
            val dy = (candidate.top + candidate.bottom) * 0.5f / height - 0.5f
            Ranked(
                value = candidate.value,
                area = abs(candidate.right - candidate.left) / width *
                    (abs(candidate.bottom - candidate.top) / height),
                centerDistanceSquared = dx * dx + dy * dy
            )
        }
        .sortedWith { a, b ->
            if (abs(a.area - b.area) > AREA_EPSILON) {
                b.area.compareTo(a.area)
            } else {
                a.centerDistanceSquared.compareTo(b.centerDistanceSquared)
            }
        }
        .map(Ranked::value)
        .distinct()
}

private class Ranked(val value: String, val area: Float, val centerDistanceSquared: Float)

private const val AREA_EPSILON = 1e-6f
