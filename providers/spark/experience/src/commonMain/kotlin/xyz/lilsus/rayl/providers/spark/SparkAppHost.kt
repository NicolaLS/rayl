package xyz.lilsus.rayl.providers.spark

import xyz.lilsus.rayl.providers.spark.application.AppBootstrapConfig
import xyz.lilsus.rayl.providers.spark.application.AppEnvironment
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletAccess
import xyz.lilsus.raylsuite.core.model.WalletStartMode

enum class SparkEnvironment {
    DEBUG,
    PRODUCTION
}

class SparkAppHost internal constructor(
    internal val bootstrapConfig: AppBootstrapConfig,
    internal val startMode: WalletStartMode,
    internal val walletAccess: WalletAccess
) {
    override fun toString(): String = "SparkAppHost(<opaque>)"
}

internal fun SparkEnvironment.toAppEnvironment(): AppEnvironment = when (this) {
    SparkEnvironment.DEBUG -> AppEnvironment.DEBUG
    SparkEnvironment.PRODUCTION -> AppEnvironment.PRODUCTION
}
