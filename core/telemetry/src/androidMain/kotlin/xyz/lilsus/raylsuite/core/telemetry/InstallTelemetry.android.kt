package xyz.lilsus.raylsuite.core.telemetry

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import xyz.lilsus.raylsuite.core.network.createHttpClient

/**
 * Starts usage data for this process; call it first in `Application.onCreate`. Events are sent
 * when the app leaves the foreground and a few seconds after a payment result.
 */
fun installTelemetry(
    application: Application,
    settings: Settings,
    mode: TelemetryMode,
    appVersion: String,
    newInstallation: () -> Boolean
) {
    val sender = TelemetrySender(
        device = TelemetryDevice(
            platform = "android",
            osMajor = Build.VERSION.SDK_INT,
            model = Build.MODEL.orEmpty().asTelemetryDeviceModel()
        ),
        appVersion = appVersion,
        client = ::createHttpClient
    )
    Telemetry.install(
        TelemetryEngine(
            settings = settings,
            mode = mode,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            newInstallation = newInstallation,
            log = { platformLog("RaylTelemetry", it) },
            send = sender::send
        )
    )
    application.registerActivityLifecycleCallbacks(FlushWhenLeavingForeground)
}

private object FlushWhenLeavingForeground : Application.ActivityLifecycleCallbacks {
    private var started = 0

    override fun onActivityStarted(activity: Activity) {
        started += 1
    }

    override fun onActivityStopped(activity: Activity) {
        started -= 1
        if (started == 0 && !activity.isChangingConfigurations) Telemetry.flush()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
