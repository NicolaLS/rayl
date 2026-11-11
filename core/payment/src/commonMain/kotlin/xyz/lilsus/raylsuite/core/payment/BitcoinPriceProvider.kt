package xyz.lilsus.raylsuite.core.payment

fun interface BitcoinPriceProvider {
    suspend fun pricePerBitcoin(fiatCurrencyCode: String): Double?
}

fun interface BitcoinPriceHistoryProvider {
    /** The last 24 hours of bitcoin's price, oldest first and evenly spaced. Null when unknown. */
    suspend fun priceHistory(fiatCurrencyCode: String): List<Double>?
}
