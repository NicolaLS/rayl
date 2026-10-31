package xyz.lilsus.rayl

import xyz.lilsus.rayl.providers.rayl.RaylPasskeys
import xyz.lilsus.rayl.providers.spark.SparkEnvironment

/** Emulators cannot complete passkey ceremonies, so this build uses a stand-in passkey. */
internal fun androidRaylConfiguration(): AndroidRaylConfiguration = AndroidRaylConfiguration(
    environment = SparkEnvironment.DEBUG,
    passkeys = RaylPasskeys.FileBacked
)
