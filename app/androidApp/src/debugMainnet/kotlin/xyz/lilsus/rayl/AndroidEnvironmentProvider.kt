package xyz.lilsus.rayl

import xyz.lilsus.rayl.providers.spark.SparkEnvironment

/** Real bitcoin with the Breez key from RAYL_BREEZ_API_KEY, like a release build. */
internal fun androidRaylConfiguration(): AndroidRaylConfiguration = AndroidRaylConfiguration(
    environment = SparkEnvironment.PRODUCTION,
    passkeys = DEVELOPMENT_PASSKEYS,
    breezApiKey = BuildConfig.BREEZ_API_KEY
)
