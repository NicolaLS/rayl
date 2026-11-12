package xyz.lilsus.raylsuite.feature.paymenthub.canvas

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Canvas grid geometry. These are plain values: Compose lays the grid out on Android and SwiftUI
 * lays the same grid out on iOS, so only the numbers are shared.
 */
object HubGrid {
    const val COLUMNS = 2
    const val ROW_HEIGHT = 92
    const val GAP = 8
    const val GUTTER = 16

    /** How long a canvas toast stays on screen, in milliseconds. */
    const val TOAST_MS = 2600L
}

data class HubGridSpan(val columns: Int, val rows: Int)

data class HubGridPlacement<T>(
    val value: T,
    val column: Int,
    val row: Int,
    val columns: Int,
    val rows: Int
)

/**
 * Dense two-column packing: every span takes the first free block scanning rows top to bottom and
 * columns left to right, so a later 1x1 backfills the hole a full-width tile left behind.
 */
fun <T> packHubGrid(
    items: List<T>,
    columns: Int = HubGrid.COLUMNS,
    span: (T) -> HubGridSpan
): List<HubGridPlacement<T>> {
    val occupied = mutableListOf<BooleanArray>()

    fun row(index: Int): BooleanArray {
        while (occupied.size <= index) occupied += BooleanArray(columns)
        return occupied[index]
    }

    fun fits(top: Int, left: Int, width: Int, height: Int): Boolean {
        if (left + width > columns) return false
        for (r in top until top + height) {
            val cells = row(r)
            for (c in left until left + width) if (cells[c]) return false
        }
        return true
    }

    return items.map { item ->
        val requested = span(item)
        val width = requested.columns.coerceIn(1, columns)
        val height = requested.rows.coerceAtLeast(1)
        var top = 0
        var left = 0
        while (true) {
            val free = (0 until columns).firstOrNull { fits(top, it, width, height) }
            if (free != null) {
                left = free
                break
            }
            top++
        }
        for (r in top until top + height) {
            val cells = row(r)
            for (c in left until left + width) cells[c] = true
        }
        HubGridPlacement(item, column = left, row = top, columns = width, rows = height)
    }
}

/** Rows the packed grid occupies, which is the canvas height in grid units. */
fun List<HubGridPlacement<*>>.gridRowCount(): Int = maxOfOrNull { it.row + it.rows } ?: 0

/**
 * The order that packs [dragged] where it is held: [x] and [y] are the middle of the carried
 * widget, in grid cells (a cell and the gap after it count as one). Every place in [order] the
 * widget could take is tried, and so is swapping it with each other widget, which reaches places a
 * move can't when widgets differ in size. The one that packs it closest to that point wins.
 *
 * Returns null to keep [order] when nothing is closer by more than [HUB_MOVE_MARGIN]: a widget
 * held still never moves, and two orders can't take turns under the finger.
 */
fun <T> hubOrderFor(
    order: List<T>,
    dragged: T,
    x: Float,
    y: Float,
    columns: Int,
    span: (T) -> HubGridSpan
): List<T>? {
    val from = order.indexOf(dragged)
    if (from < 0) return null
    fun distance(candidate: List<T>): Float {
        val placed = packHubGrid(candidate, columns, span).first { it.value == dragged }
        return hypot(placed.column + placed.columns / 2f - x, placed.row + placed.rows / 2f - y)
    }

    class Candidate<T>(val items: List<T>, val distance: Float, val swap: Boolean, val shift: Int)
    val others = order.filterIndexed { index, _ -> index != from }
    val moves = (0..others.size).map { index ->
        val moved = others.toMutableList().apply { add(index, dragged) }
        Candidate(moved, distance(moved), swap = false, shift = abs(index - from))
    }
    val swaps = order.indices.filter { it != from }.map { index ->
        val swapped = order.toMutableList().apply {
            this[from] = order[index]
            this[index] = dragged
        }
        Candidate(swapped, distance(swapped), swap = true, shift = abs(index - from))
    }
    // A move, the others closing up behind it, beats a swap that lands just as close; of either,
    // the one that disturbs the order least.
    val best = (moves + swaps).minWith(compareBy({ it.distance }, { it.swap }, { it.shift }))
    return best.items.takeIf { best.distance < distance(order) - HUB_MOVE_MARGIN }
}

/** How much closer, in grid cells, another place must be before a carried widget moves there. */
const val HUB_MOVE_MARGIN = 0.25f
