package xyz.lilsus.rayl

import xyz.lilsus.rayl.providers.spark.SparkEnvironment

internal fun androidRaylConfiguration(): AndroidRaylConfiguration = AndroidRaylConfiguration(
    environment = SparkEnvironment.DEBUG,
    passkeys = DEVELOPMENT_PASSKEYS
)
