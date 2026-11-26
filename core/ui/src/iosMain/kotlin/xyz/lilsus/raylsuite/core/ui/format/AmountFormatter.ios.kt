package xyz.lilsus.raylsuite.core.ui.format

import kotlin.concurrent.Volatile
import platform.Foundation.NSDecimalNumber
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterCurrencyStyle
import platform.Foundation.NSNumberFormatterDecimalStyle
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency

@Volatile
private var cachedFormatter: Pair<String, IosAmountFormatter>? = null

internal actual fun createAmountFormatter(locale: AppLocale): AmountFormatter {
    cachedFormatter?.let { (tag, formatter) ->
        if (tag == locale.languageTag) return formatter
    }
    return IosAmountFormatter(locale).also { cachedFormatter = locale.languageTag to it }
}

private class IosAmountFormatter(locale: AppLocale) : AmountFormatter {
    private val nsLocale = NSLocale(localeIdentifier = locale.languageTag)
    private val fiatFormatters = mutableMapOf<String, NSNumberFormatter>()
    private val satoshiFormatter by lazy {
        NSNumberFormatter().apply {
            numberStyle = NSNumberFormatterDecimalStyle
            minimumFractionDigits = 0u
            maximumFractionDigits = 0u
            usesGroupingSeparator = true
            this.locale = nsLocale
        }
    }

    override fun format(amount: DisplayAmount): String = when (val currency = amount.currency) {
        is DisplayCurrency.Fiat -> formatFiat(amount.minor, currency)
        DisplayCurrency.Satoshi -> formatSatoshi(amount.minor)
    }

    override fun affix(currency: DisplayCurrency): CurrencyAffix = when (currency) {
        DisplayCurrency.Satoshi -> CurrencyAffix(prefix = "", suffix = SATOSHI_SUFFIX)

        is DisplayCurrency.Fiat ->
            fiatFormatter(currency).let { formatter ->
                CurrencyAffix(formatter.positivePrefix, formatter.positiveSuffix)
            }
    }

    private fun fiatFormatter(currency: DisplayCurrency.Fiat): NSNumberFormatter {
        val code = currency.iso4217.uppercase()
        return fiatFormatters.getOrPut(code) {
            NSNumberFormatter().apply {
                numberStyle = NSNumberFormatterCurrencyStyle
                locale = nsLocale
                currencyCode = code
            }
        }
    }

    private fun formatFiat(minor: Long, currency: DisplayCurrency.Fiat): String {
        val formatter = fiatFormatter(currency)
        val fractionDigits = formatter.maximumFractionDigits.toInt()
        val decimalMinor = NSDecimalNumber(string = minor.toString())
        val major =
            decimalMinor.decimalNumberByMultiplyingByPowerOf10((-fractionDigits).toShort())
        return formatter.stringFromNumber(major) ?: "$minor ${currency.iso4217.uppercase()}"
    }

    private fun formatSatoshi(minor: Long): String {
        val number = NSNumber(longLong = minor)
        val formatted = satoshiFormatter.stringFromNumber(number) ?: minor.toString()
        return "$formatted$SATOSHI_SUFFIX"
    }
}
