package xyz.lilsus.rayl.providers.blink.feature.walletsettings

import xyz.lilsus.rayl.providers.blink.ui.nativeBlinkErrorMessageFor
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

data class NativeBlinkWalletSettingsText(
    val fundingWalletTitle: String,
    val fundingWalletPickerTitle: String,
    val chooseFundingWalletTitle: String,
    val bitcoinTitle: String,
    val stablesatsTitle: String,
    val loadingTitle: String,
    val unavailableMessage: String,
    val closeTitle: String,
    val errorMessage: String?
)

/** Resolves Blink-owned wallet Settings presentation without leaking generated resources. */
suspend fun nativeBlinkWalletSettingsText(
    state: BlinkWalletSettingsUiState,
    appName: String
): NativeBlinkWalletSettingsText = NativeBlinkWalletSettingsText(
    fundingWalletTitle = nativeString(
        NativeStringResource(
            table = "BlinkWalletSettings",
            key = "settings_blink_funding_wallet"
        )
    ),
    fundingWalletPickerTitle = nativeString(
        NativeStringResource(
            table = "BlinkWalletSettings",
            key = "settings_blink_funding_wallet_title"
        )
    ),
    chooseFundingWalletTitle = nativeString(
        NativeStringResource(
            table = "BlinkWalletSettings",
            key = "settings_blink_funding_wallet_choose"
        )
    ),
    bitcoinTitle = nativeString(
        NativeStringResource(
            table = "BlinkWalletSettings",
            key = "settings_blink_funding_wallet_bitcoin"
        )
    ),
    stablesatsTitle = nativeString(
        NativeStringResource(
            table = "BlinkWalletSettings",
            key = "settings_blink_funding_wallet_stablesats"
        )
    ),
    loadingTitle = nativeString(
        NativeStringResource(
            table = "BlinkWalletSettings",
            key = "settings_blink_funding_wallet_loading"
        )
    ),
    unavailableMessage = nativeString(
        NativeStringResource(
            table = "BlinkWalletSettings",
            key = "settings_blink_funding_wallet_unavailable"
        )
    ),
    closeTitle = nativeString(
        NativeStringResource(
            table = "BlinkWalletSettings",
            key = "settings_blink_funding_wallet_close"
        )
    ),
    errorMessage = state.error?.let { nativeBlinkErrorMessageFor(it) }
)
