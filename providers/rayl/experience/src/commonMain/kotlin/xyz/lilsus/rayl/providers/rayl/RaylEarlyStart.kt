package xyz.lilsus.rayl.providers.rayl

import xyz.lilsus.rayl.providers.rayl.integration.pocket.RaylPocket
import xyz.lilsus.raylsuite.core.telemetry.RaylTrace

/**
 * Starts the pocket at process start, before any UI exists, so connecting overlaps app launch and
 * aiming the camera. The experience takes the pocket over when it mounts. Main thread only.
 *
 * TODO: Measure on devices whether pre-warming the Spark session after connect speeds up the
 * first payment. On hosted Regtest from a desktop, connect took ~1.2 s and the first operation
 * ~3-5 s versus ~1.5 s for later ones; a full sync absorbed that but took 11-13 s, and cheap
 * calls (user settings, fees, deposits) did not help.
 */
internal object RaylEarlyStart {
    private var pocket: RaylPocket? = null

    fun start(create: () -> RaylPocket) {
        if (pocket == null) pocket = create().also(RaylPocket::start)
    }

    fun take(create: () -> RaylPocket): RaylPocket = pocket?.also { pocket = null } ?: create()
}

/** Debug builds: log payment-path timings under the `RaylTiming` tag. Call first at launch. */
fun enableRaylTimings() {
    RaylTrace.debugLog = true
    RaylTrace.debug("process start")
}
