package xyz.lilsus.rayl.providers.spark.integration.wallet.spark

import breez_sdk_spark.ConnectRequest
import breez_sdk_spark.Network
import breez_sdk_spark.Seed
import breez_sdk_spark.connect
import xyz.lilsus.rayl.providers.spark.application.AppBootstrapConfig
import xyz.lilsus.rayl.providers.spark.application.wallet.SparkSdkConnector
import xyz.lilsus.rayl.providers.spark.application.wallet.SparkSdkSession
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletCredential

object BreezSparkSdkConnector : SparkSdkConnector {
    override suspend fun connect(
        credential: WalletCredential,
        storageDirectory: String,
        freshStorage: Boolean,
        bootstrapConfig: AppBootstrapConfig
    ): SparkSdkSession {
        val config = bootstrapConfig.sparkConfig()
        val sdk = connect(
            ConnectRequest(
                config = config,
                seed = Seed.Mnemonic(credential.mnemonic, credential.passphrase),
                storageDir = storageDirectory
            )
        )
        return object : SparkSdkSession {
            override val paymentClient = BreezSparkPaymentClient(
                sdk = sdk,
                freshStorage = freshStorage,
                preferSpark = config.network == Network.REGTEST
            )

            override suspend fun disconnect() = sdk.disconnect()
        }
    }
}
