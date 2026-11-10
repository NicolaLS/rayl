package xyz.lilsus.rayl

import xyz.lilsus.raylsuite.core.settings.createAppSettings
import xyz.lilsus.raylsuite.core.telemetry.TelemetryMode
import xyz.lilsus.raylsuite.core.telemetry.installTelemetry

/**
 * Starts Rayl's anonymous usage data for this process. Call it before anything touches
 * [RaylIosApp], whose creation already reports the first onboarding step.
 *
 * [mode] is `upload` for the store app, `log` for debug builds (events are logged under
 * `RaylTelemetry` and never sent) and anything else for automated builds, which keep nothing.
 */
fun configureRaylTelemetry(mode: String, appVersion: String) {
    val settings = createAppSettings()
    installTelemetry(
        settings = settings,
        mode = when (mode) {
            "upload" -> TelemetryMode.Upload
            "log" -> TelemetryMode.Log
            else -> TelemetryMode.Off
        },
        appVersion = appVersion,
        // Only installations that are not onboarded yet report onboarding steps.
        newInstallation = { !appOnboarded(settings) }
    )
}
