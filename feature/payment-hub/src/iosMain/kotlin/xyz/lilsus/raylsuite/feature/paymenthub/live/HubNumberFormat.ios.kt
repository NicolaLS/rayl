package xyz.lilsus.raylsuite.feature.paymenthub.live

import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import xyz.lilsus.raylsuite.core.ui.format.currentAppLocale

internal actual fun formatHubDecimal(value: Double, fractionDigits: Int): String =
    NSNumberFormatter().apply {
        numberStyle = NSNumberFormatterDecimalStyle
        minimumFractionDigits = fractionDigits.toULong()
        maximumFractionDigits = fractionDigits.toULong()
        usesGroupingSeparator = true
        locale = NSLocale(localeIdentifier = currentAppLocale().languageTag)
    }.stringFromNumber(NSNumber(double = value)) ?: value.toString()
