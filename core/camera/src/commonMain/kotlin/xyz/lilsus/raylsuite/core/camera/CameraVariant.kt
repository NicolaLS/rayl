package xyz.lilsus.raylsuite.core.camera

import kotlin.random.Random
import xyz.lilsus.raylsuite.core.raylapi.TelemetryCameraVariant
import xyz.lilsus.raylsuite.core.telemetry.Telemetry

/**
 * Scanner configurations compared on real devices. Each process draws one by [weight] when its
 * first scanner starts and reports it with its scan timings (see `TelemetryCatalog.scan`), so two
 * configurations can be compared per device model without a server or anything stored.
 *
 * To try a configuration, add a variant with a small weight, give it its settings in
 * `androidCameraConfig` and `iosCameraConfig`, and list its [telemetry] value in the catalogue.
 * A variant that turns out worse stays in the field until the next release, so add only what
 * already won in the lab (`./gradlew perfCheck`).
 */
internal enum class CameraVariant(val telemetry: TelemetryCameraVariant, val weight: Int) {
    Control(TelemetryCameraVariant.Control, weight = 100);

    companion object {
        /** This process's variant. */
        val current: CameraVariant by lazy {
            val options = entries.map { it to it.weight }
            pickWeighted(options, roll = Random.nextInt(options.sumOf { it.second }))
                .also { Telemetry.cameraVariant = it.telemetry }
        }
    }
}

/** The option whose share of the weights contains [roll], which is below their sum. */
internal fun <T> pickWeighted(options: List<Pair<T, Int>>, roll: Int): T {
    var remaining = roll
    for ((option, weight) in options) {
        if (remaining < weight) return option
        remaining -= weight
    }
    error("roll $roll is not below the total weight")
}
