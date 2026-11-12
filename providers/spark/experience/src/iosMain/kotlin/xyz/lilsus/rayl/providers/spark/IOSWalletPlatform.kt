package xyz.lilsus.rayl.providers.spark

import xyz.lilsus.rayl.providers.spark.application.AppBootstrapConfig
import xyz.lilsus.rayl.providers.spark.integration.wallet.platform.SPARK_CREDENTIAL_SERVICE
import xyz.lilsus.rayl.providers.spark.integration.wallet.platform.clearIOSWalletStorage
import xyz.lilsus.rayl.providers.spark.integration.wallet.platform.createIOSWalletAccess
import xyz.lilsus.rayl.providers.spark.integration.wallet.platform.sparkCredentialService
import xyz.lilsus.raylsuite.core.model.WalletStartMode

fun createIOSAppHost(
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
        walletAccess = createIOSWalletAccess(bootstrapConfig, startMode, storageName)
    )
}

suspend fun clearSavedSparkWallet(storageName: String? = null): Boolean =
    clearIOSWalletStorage(storageName)

/** Keychain services of the Spark wallets named [storageNames], `null` being 1.1's single one. */
fun sparkCredentialStores(storageNames: List<String?>): Set<String> =
    storageNames.map(::sparkCredentialService).toSet()

/** The prefix of every Spark wallet's Keychain service. */
val SPARK_CREDENTIAL_STORE_PREFIX: String = SPARK_CREDENTIAL_SERVICE
