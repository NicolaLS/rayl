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

/** Each Blink card's own storage; the legacy card keeps 1.1's names. */
internal fun RaylCard.blinkConfiguration(): BlinkExperienceConfiguration {
    check(wallet == ExternalWallet.Blink)
    val suffix = storageSuffix ?: return RAYL_BLINK
    return RAYL_BLINK.copy(
        walletPreferencesName = "${RAYL_BLINK.walletPreferencesName}.$suffix",
        credentialsName = "${RAYL_BLINK.credentialsName}.$suffix"
    )
}

/** Each NWC card's own storage; the legacy card keeps 1.1's names. */
internal fun RaylCard.nwcConfiguration(): NwcExperienceConfiguration {
    check(wallet == ExternalWallet.Nwc)
    val suffix = storageSuffix ?: return RAYL_NWC
    return RAYL_NWC.copy(
        walletPreferencesName = "${RAYL_NWC.walletPreferencesName}.$suffix",
        credentialsName = "${RAYL_NWC.credentialsName}.$suffix"
    )
}

/** Each Spark card's own storage; the legacy card keeps 1.1's locations. */
internal fun RaylCard.sparkConfiguration(): SparkExperienceConfiguration {
    check(wallet == ExternalWallet.Spark)
    return RAYL_SPARK.copy(storageName = storageSuffix)
}

/** Keychain services of [cards], which survive an uninstall; see [RaylCards.prepare]. */
internal fun credentialStores(cards: List<RaylCard>): Set<String> = cards.mapNotNull { card ->
    when (card.wallet) {
        ExternalWallet.Blink -> card.blinkConfiguration().credentialsName

        ExternalWallet.Nwc -> card.nwcConfiguration().credentialsName

        // Spark names its Keychain services itself; see the iOS cleanup.
        ExternalWallet.Spark -> null
    }
}.toSet()

/** Prefixes of the Blink and NWC cards' Keychain services, legacy ones included. */
internal val CREDENTIAL_STORE_PREFIXES =
    listOf(RAYL_BLINK.credentialsName, RAYL_NWC.credentialsName)
