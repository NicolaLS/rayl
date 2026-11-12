package xyz.lilsus.rayl.providers.spark

import xyz.lilsus.raylsuite.feature.settings.SettingsLegalLinks

/** Product identity, app preferences, and installation-local storage, without provider policy. */
data class SparkExperienceConfiguration(
    val appName: String,
    val preferencesName: String,
    val legalLinks: SettingsLegalLinks,
    /**
     * Keeps this wallet's recovery phrase and SDK data apart from other Spark wallets'; `null` is
     * the storage Rayl 1.1 used for its single Spark wallet.
     */
    val storageName: String? = null
)
