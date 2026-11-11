package xyz.lilsus.raylsuite.feature.paymenthub.live

import kotlin.math.abs

/** A grouped number in the app's language, for figures that are not money. */
internal expect fun formatHubDecimal(value: Double, fractionDigits: Int): String

/** The day's move as "+0.69%", or null while the chart is unknown. */
fun HubRate.changeLabel(): String? = changePercent?.let {
    (if (it < 0) "−" else "+") + formatHubDecimal(abs(it), 2) + "%"
}

/** How many sats one unit of the currency buys. */
fun HubRate.satsLabel(): String = formatHubDecimal(satsPerUnit, satsFractionDigits())
