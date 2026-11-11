package xyz.lilsus.raylsuite.feature.paymenthub.live

import java.text.NumberFormat
import java.util.Locale
import xyz.lilsus.raylsuite.core.ui.format.currentAppLocale

internal actual fun formatHubDecimal(value: Double, fractionDigits: Int): String =
    NumberFormat.getNumberInstance(Locale.forLanguageTag(currentAppLocale().languageTag)).apply {
        minimumFractionDigits = fractionDigits
        maximumFractionDigits = fractionDigits
    }.format(value)
