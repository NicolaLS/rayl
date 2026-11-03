package xyz.lilsus.rayl

import xyz.lilsus.rayl.providers.blink.BlinkExperienceConfiguration
import xyz.lilsus.rayl.providers.nwc.NwcExperienceConfiguration
import xyz.lilsus.rayl.providers.rayl.RaylEnvironment
import xyz.lilsus.rayl.providers.rayl.RaylExperienceConfiguration
import xyz.lilsus.rayl.providers.spark.SparkEnvironment
import xyz.lilsus.rayl.providers.spark.SparkExperienceConfiguration
import xyz.lilsus.raylsuite.feature.settings.SettingsLegalLinks

internal const val RAYL_PREFERENCES = "rayl_preferences"
internal val RAYL_LEGAL_LINKS = SettingsLegalLinks(
    privacyPolicyUrl =
        "https://github.com/NicolaLS/rayl/blob/main/distribution/privacy.md",
    termsUrl = "https://github.com/NicolaLS/rayl/blob/main/distribution/terms.md",
    sourceCodeUrl = "https://github.com/NicolaLS/rayl"
)
internal val RAYL_BLINK = BlinkExperienceConfiguration(
    appName = "Rayl",
    preferencesName = RAYL_PREFERENCES,
    walletPreferencesName = "rayl_blink_connection",
    credentialsName = "rayl_blink_credentials",
    legalLinks = RAYL_LEGAL_LINKS
)
internal val RAYL_NWC = NwcExperienceConfiguration(
    appName = "Rayl",
    preferencesName = RAYL_PREFERENCES,
    walletPreferencesName = "rayl_nwc_connection",
    credentialsName = "rayl_nwc_credentials",
    legalLinks = RAYL_LEGAL_LINKS
)
internal val RAYL_SPARK = SparkExperienceConfiguration(
    appName = "Rayl",
    preferencesName = RAYL_PREFERENCES,
    legalLinks = RAYL_LEGAL_LINKS
)
internal val RAYL_POCKET = RaylExperienceConfiguration(
    appName = "Rayl",
    preferencesName = RAYL_PREFERENCES,
    legalLinks = RAYL_LEGAL_LINKS
)

/** Rayl's pocket runs on the same build-selected network as the Spark provider. */
internal fun SparkEnvironment.toRaylEnvironment(): RaylEnvironment = when (this) {
    SparkEnvironment.DEBUG -> RaylEnvironment.DEBUG
    SparkEnvironment.PRODUCTION -> RaylEnvironment.PRODUCTION
}
