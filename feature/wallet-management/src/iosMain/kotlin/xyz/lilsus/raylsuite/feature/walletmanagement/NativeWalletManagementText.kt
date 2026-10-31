package xyz.lilsus.raylsuite.feature.walletmanagement

import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

data class NativeWalletManagementText(
    val removeTitle: String,
    /** Why going back to Rayl is unavailable while a payment is submitted. */
    val removeBusyMessage: String,
    val cancelTitle: String
)

suspend fun nativeWalletManagementText(): NativeWalletManagementText = NativeWalletManagementText(
    removeTitle = nativeString(
        NativeStringResource(table = "WalletManagement", key = "settings_manage_wallet_remove")
    ),
    removeBusyMessage = nativeString(
        NativeStringResource(table = "WalletManagement", key = "wallet_removal_payment_active")
    ),
    cancelTitle = nativeString(
        NativeStringResource(table = "WalletManagement", key = "settings_manage_wallet_cancel")
    )
)
