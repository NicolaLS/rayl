package xyz.lilsus.rayl

import xyz.lilsus.rayl.providers.rayl.RaylPasskeys
import xyz.lilsus.rayl.providers.spark.SparkEnvironment

/**
 * Breez's shared relying party (the SDK's `PasskeyProvider.BREEZ_RP_ID`). Breez lists
 * `com.nicolasusca.rayl` with the SHA-256 fingerprint of the certificate that signs installed
 * builds. Changing the relying party changes every user's wallet.
 */
internal fun androidRaylConfiguration(): AndroidRaylConfiguration = AndroidRaylConfiguration(
    environment = SparkEnvironment.PRODUCTION,
    passkeys = RaylPasskeys.Platform(rpId = "keys.breez.technology"),
    breezApiKey = BuildConfig.BREEZ_API_KEY
)
