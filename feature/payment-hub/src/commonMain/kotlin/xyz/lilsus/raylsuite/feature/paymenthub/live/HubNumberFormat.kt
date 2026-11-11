package xyz.lilsus.raylsuite.feature.paymenthub.live

import kotlin.math.abs
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.ui.format.AmountFormatter

/** A grouped number in the app's language, for figures that are not money. */
internal expect fun formatHubDecimal(value: Double, fractionDigits: Int): String

/** The day's move as "+0.69%", or null while the chart is unknown. */
fun HubRate.changeLabel(): String? = changePercent?.let {
    (if (it < 0) "−" else "+") + formatHubDecimal(abs(it), 2) + "%"
}

/**
 * The price in whole units, as "$73,924". Every widget figure is set in one size, and a price
 * with cents would be the only one too long for it.
 */
fun HubRate.priceLabel(formatter: AmountFormatter): String {
    val affix = formatter.affix(CurrencyCatalog.infoFor(currencyCode).currency)
    return affix.prefix + formatHubDecimal(price, 0) + affix.suffix
}

/** How many sats one unit of the currency buys. */
fun HubRate.satsLabel(): String = formatHubDecimal(satsPerUnit, satsFractionDigits())
