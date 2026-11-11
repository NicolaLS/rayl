package xyz.lilsus.raylsuite.feature.paymenthub.live

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.feature.paymenthub.create.cleanAmountInput
import xyz.lilsus.raylsuite.feature.paymenthub.create.parseMinorAmount

/** Bitcoin's price in one fiat currency, with the last day's prices when the chart is known. */
data class HubRate(
    val currencyCode: String,
    val price: Double,
    /** Oldest first and evenly spaced; empty until the chart loaded. */
    val history: List<Double> = emptyList()
) {
    /** How far the price moved over [history], in percent. */
    val changePercent: Double?
        get() = history.firstOrNull()?.takeIf { it > 0 }?.let { (price - it) / it * 100 }

    val satsPerUnit: Double get() = SATS_PER_BITCOIN / price

    /** The price as an amount the app's formatter can write. */
    fun priceAmount(): DisplayAmount {
        val info = CurrencyCatalog.infoFor(currencyCode)
        return DisplayAmount((price * 10.0.pow(info.fractionDigits)).roundToLong(), info.currency)
    }

    /** [history] ending at the current price, scaled to 0..1 for drawing. Empty without a chart. */
    fun chart(): List<Float> {
        if (history.size < 2) return emptyList()
        val points = history + price
        val low = points.min()
        val range = points.max() - low
        return points.map { if (range <= 0) 0.5f else ((it - low) / range).toFloat() }
    }

    companion object {
        /** A plausible day for gallery previews shown before any price loaded. */
        fun sample(currencyCode: String): HubRate {
            val base = if (CurrencyCatalog.infoFor(currencyCode).fractionDigits == 0) {
                13_250_000.0
            } else {
                84_310.0
            }
            val shape = listOf(
                0.36, 0.33, 0.30, 0.32, 0.27, 0.29, 0.25, 0.28, 0.31, 0.29, 0.33, 0.30, 0.34,
                0.32, 0.36, 0.33, 0.31, 0.35, 0.33, 0.37, 0.35, 0.40, 0.44, 0.60, 0.82
            )
            val history = shape.map { base * (1 + (it - 0.36) * 0.03) }
            return HubRate(currencyCode, base * (1 + (1.0 - 0.36) * 0.03), history)
        }
    }
}

/**
 * The rate a widget face shows. A [preview] is a picture of the widget, so it shows a sample day
 * until both the price and its chart are known; a placed widget only ever shows real figures.
 */
fun HubRate?.forFace(currencyCode: String, preview: Boolean): HubRate? =
    if (preview && (this == null || history.size < 2)) HubRate.sample(currencyCode) else this

enum class HubPpqProblem { InvalidKey, Unreachable }

enum class HubPpqTopUpError { Amount, InvalidKey, Unreachable }

/** The ppq.ai account every PPQ.AI widget shows. */
data class HubPpqAccount(
    /** An API key is stored on this device. */
    val connected: Boolean = false,
    /** The last balance ppq.ai reported; kept while a refresh fails. */
    val balanceUsd: Double? = null,
    val problem: HubPpqProblem? = null,
    val toppingUp: Boolean = false,
    val topUpError: HubPpqTopUpError? = null,
    /** Counts invoices handed to the wallet, so an open top-up sheet knows to close. */
    val topUpSerial: Int = 0
) {
    fun balanceAmount(): DisplayAmount? = balanceUsd?.let {
        DisplayAmount((it * 100).roundToLong(), CurrencyCatalog.infoFor("USD").currency)
    }

    companion object {
        /** Top-ups ppq.ai accepts over Lightning, in US cents. */
        val TOP_UP_CENTS = 10L..100_000L
        val TOP_UP_PRESETS_USD = listOf(5, 10, 25, 50)
    }
}

/** The feedback form: whether a message is on its way and how the last one went. */
data class HubFeedbackState(
    val sending: Boolean = false,
    val failed: Boolean = false,
    /** Counts messages the backend kept, so an open form knows to close. */
    val sentSerial: Int = 0
)

/** Both sides of the converter, as the text its two fields show. */
data class HubConversion(val fiat: String, val sats: String)

/** The user typed a fiat amount: tidy it and work out the sats. */
fun HubRate.convertFiat(input: String): HubConversion {
    val digits = CurrencyCatalog.infoFor(currencyCode).fractionDigits
    val cleaned = input.cleanAmountInput(digits).take(MAX_INPUT_LENGTH)
    val minor = cleaned.parseMinorAmount(digits)
    val sats = minor?.let { (it / 10.0.pow(digits) * satsPerUnit).roundToLong() }
    return HubConversion(cleaned, sats?.toString().orEmpty())
}

/** The user typed sats: tidy them and work out the fiat amount. */
fun HubRate.convertSats(input: String): HubConversion {
    val digits = CurrencyCatalog.infoFor(currencyCode).fractionDigits
    val cleaned = input.filter(Char::isDigit).take(MAX_INPUT_LENGTH)
    val minor = cleaned.toLongOrNull()?.let {
        (it / satsPerUnit * 10.0.pow(digits)).roundToLong()
    }
    return HubConversion(minor?.plainAmount(digits).orEmpty(), cleaned)
}

/** How many decimals a sats-per-unit figure needs: small figures would round away otherwise. */
fun HubRate.satsFractionDigits(): Int = if (abs(satsPerUnit) < 100) 2 else 0

/** Money as a field shows it: every minor digit, no grouping. */
private fun Long.plainAmount(fractionDigits: Int): String {
    if (fractionDigits <= 0) return toString()
    val text = toString().padStart(fractionDigits + 1, '0')
    return text.dropLast(fractionDigits) + "." + text.takeLast(fractionDigits)
}

private const val SATS_PER_BITCOIN = 100_000_000.0
private const val MAX_INPUT_LENGTH = 12
