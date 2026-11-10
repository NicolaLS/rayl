package xyz.lilsus.raylsuite.core.telemetry

import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIBackgroundTaskInvalid
import xyz.lilsus.raylsuite.core.network.createHttpClient

/**
 * Starts usage data for this process; call it first at app initialisation. Events are sent when
 * the app leaves the foreground, a few seconds after a payment result, and a while after a scan.
 */
fun installTelemetry(
    settings: Settings,
    mode: TelemetryMode,
    appVersion: String,
    newInstallation: () -> Boolean
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sender = TelemetrySender(
        device = TelemetryDevice(
            platform = "ios",
            osMajor = iosMajorVersion(),
            model = iosDeviceModel().asTelemetryDeviceModel()
        ),
        appVersion = appVersion,
        client = ::createHttpClient
    )
    Telemetry.install(
        TelemetryEngine(
            settings = settings,
            mode = mode,
            scope = scope,
            newInstallation = newInstallation,
            log = { platformLog("RaylTelemetry", it) },
            send = sender::send
        )
    )
    // Leaving the foreground starts with "will resign active": that is all an app gets when it is
    // swiped away from the app switcher, which never puts it in the background first.
    listOf(
        UIApplicationWillResignActiveNotification,
        UIApplicationDidEnterBackgroundNotification
    ).forEach { notification ->
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = notification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { _ ->
            // iOS may suspend the app moments after this; the task keeps it alive for the request.
            val application = UIApplication.sharedApplication
            var task = UIBackgroundTaskInvalid
            fun end() {
                if (task != UIBackgroundTaskInvalid) application.endBackgroundTask(task)
                task = UIBackgroundTaskInvalid
            }
            task = application.beginBackgroundTaskWithName("rayl.usage-data", ::end)
            scope.launch {
                try {
                    Telemetry.flushNow()
                } finally {
                    launch(Dispatchers.Main) { end() }
                }
            }
        }
    }
}
