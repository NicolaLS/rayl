package xyz.lilsus.rayl.providers.rayl

import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketConfig
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketNetwork
import xyz.lilsus.raylsuite.feature.settings.SettingsLegalLinks

/** Product identity and app preferences, without provider policy. */
data class RaylExperienceConfiguration(
    val appName: String,
    val preferencesName: String,
    val legalLinks: SettingsLegalLinks
)

enum class RaylEnvironment {
    /** Regtest pocket. */
    DEBUG,
    PRODUCTION
}

internal fun RaylEnvironment.pocketConfig(breezApiKey: String?): PocketConfig = PocketConfig(
    network = when (this) {
        RaylEnvironment.DEBUG -> PocketNetwork.Regtest
        RaylEnvironment.PRODUCTION -> PocketNetwork.Mainnet
    },
    breezApiKey = breezApiKey
)

/** Where Rayl's wallet keeps the passkey that derives its key. */
sealed interface RaylPasskeys {
    /** The device's passkeys for the relying party [rpId], whose domain lists this app. */
    data class Platform(val rpId: String) : RaylPasskeys

    /** A stand-in secret on the device, for emulators and automated builds. */
    data object FileBacked : RaylPasskeys
}
