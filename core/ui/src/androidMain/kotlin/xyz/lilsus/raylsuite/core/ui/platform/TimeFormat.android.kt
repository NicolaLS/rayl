package xyz.lilsus.raylsuite.core.ui.platform

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val cachedFormatter = object : ThreadLocal<Pair<Locale, SimpleDateFormat>>() {}

actual fun formatTimeHHmm(epochMillis: Long): String {
    val locale = Locale.getDefault()
    val cached = cachedFormatter.get()
    val formatter = if (cached != null && cached.first == locale) {
        cached.second
    } else {
        SimpleDateFormat("HH:mm", locale).also { cachedFormatter.set(locale to it) }
    }
    return formatter.format(Date(epochMillis))
}
