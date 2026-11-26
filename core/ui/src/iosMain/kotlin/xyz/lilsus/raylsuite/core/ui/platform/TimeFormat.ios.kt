package xyz.lilsus.raylsuite.core.ui.platform

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970

private val timeFormatter by lazy {
    NSDateFormatter().apply {
        dateFormat = "HH:mm"
    }
}

actual fun formatTimeHHmm(epochMillis: Long): String {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)
    return timeFormatter.stringFromDate(date)
}
