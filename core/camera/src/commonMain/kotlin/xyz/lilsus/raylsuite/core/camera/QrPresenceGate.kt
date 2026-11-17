package xyz.lilsus.raylsuite.core.camera

/**
 * Hands a code on once for as long as it stays in view. A reader sees the same code in frame
 * after frame; it counts as gone, and as new when it shows again, only after
 * [absentObservationsToRearm] observations without it, because a reader also misses a code that
 * is still there now and then.
 */
internal class QrPresenceGate(
    private val absentObservationsToRearm: Int = DEFAULT_ABSENT_OBSERVATIONS_TO_REARM
) {
    /** The codes in view, with how many observations in a row each has been missing. */
    private val missed = mutableMapOf<String, Int>()

    init {
        require(absentObservationsToRearm > 0) {
            "absentObservationsToRearm must be positive"
        }
    }

    /** [values] are the codes read now. Returns those that just came into view, in that order. */
    fun observe(values: List<String>): List<String> {
        val entries = missed.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.key in values) continue
            entry.setValue(entry.value + 1)
            if (entry.value >= absentObservationsToRearm) entries.remove()
        }

        val arrived = values.filter { it !in missed }.distinct()
        values.forEach { missed[it] = 0 }
        return arrived
    }

    fun reset() {
        missed.clear()
    }

    private companion object {
        const val DEFAULT_ABSENT_OBSERVATIONS_TO_REARM = 3
    }
}
