package xyz.lilsus.rayl

import xyz.lilsus.rayl.providers.spark.SparkEnvironment

/**
 * TODO(breez): Before release, register the production app with Breez for its shared relying
 * party, then use `RaylPasskeys.Platform(PasskeyProvider.BREEZ_RP_ID)` ("keys.breez.technology").
 * Send Breez the package `com.nicolasusca.rayl` and the SHA-256 fingerprint of the certificate
 * that signs installed builds (Google's app signing key with Play App Signing). Changing the
 * relying party later changes every user's wallet, so it must be final before release. Until
 * then, release builds use the development relying party, whose `assetlinks.json` does not list
 * this package yet, so they offer only external wallets.
 */
internal fun androidRaylConfiguration(): AndroidRaylConfiguration = AndroidRaylConfiguration(
    environment = SparkEnvironment.PRODUCTION,
    passkeys = DEVELOPMENT_PASSKEYS,
    breezApiKey = BuildConfig.BREEZ_API_KEY
)
