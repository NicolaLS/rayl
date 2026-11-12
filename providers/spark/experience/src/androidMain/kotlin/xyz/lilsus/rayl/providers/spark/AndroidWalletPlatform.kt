package xyz.lilsus.rayl.providers.spark

import android.content.Context
import xyz.lilsus.rayl.providers.spark.application.AppBootstrapConfig
import xyz.lilsus.rayl.providers.spark.integration.wallet.platform.clearAndroidWalletStorage
import xyz.lilsus.rayl.providers.spark.integration.wallet.platform.createAndroidWalletAccess
import xyz.lilsus.raylsuite.core.model.WalletStartMode

fun createAndroidAppHost(
    context: Context,
    environment: SparkEnvironment,
    startMode: WalletStartMode,
    breezApiKey: String? = null,
    /** See [SparkExperienceConfiguration.storageName]. */
    storageName: String? = null
): SparkAppHost {
    val bootstrapConfig = AppBootstrapConfig(environment.toAppEnvironment(), breezApiKey)
    return SparkAppHost(
        bootstrapConfig = bootstrapConfig,
        startMode = startMode,
        walletAccess = createAndroidWalletAccess(context, bootstrapConfig, startMode, storageName)
    )
}

suspend fun clearSavedSparkWallet(context: Context, storageName: String? = null): Boolean =
    clearAndroidWalletStorage(context, storageName)
