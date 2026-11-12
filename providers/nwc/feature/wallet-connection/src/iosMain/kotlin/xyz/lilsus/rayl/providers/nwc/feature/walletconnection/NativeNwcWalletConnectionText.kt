package xyz.lilsus.rayl.providers.nwc.feature.walletconnection

import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

data class NativeNwcWalletConnectionText(
    val addTitle: String,
    val addDescription: String,
    val uriLabel: String,
    val uriPlaceholder: String,
    val paste: String,
    val scanInstruction: String,
    val scanPermission: String,
    val scanAllowCamera: String,
    val scanOpenSettings: String,
    val scanRestricted: String,
    val scanUnavailable: String,
    val confirmTitle: String,
    val confirmDescription: String,
    val cancel: String,
    val confirm: String,
    val aliasLabel: String,
    val relayLabel: String,
    val lightningAddressLabel: String,
    val loading: String,
    val retry: String,
    val warningHeading: String,
    val warningMissingPayInvoice: String,
    val warningMissingLookupInvoice: String,
    val warningMissingNip44: String,
    val warningLegacyNip04: String,
    val warningLegacyNip04Default: String,
    val requiredMethods: String,
    val errorAlreadyConnected: String,
    val errorInvalidUri: String,
    val errorConnection: String
)

suspend fun nativeNwcWalletConnectionText(appName: String): NativeNwcWalletConnectionText =
    NativeNwcWalletConnectionText(
        addTitle = nativeString(
            NativeStringResource(table = "NwcWalletConnection", key = "add_wallet_title")
        ),
        addDescription = nativeString(
            NativeStringResource(table = "NwcWalletConnection", key = "add_wallet_description")
        ),
        uriLabel = nativeString(
            NativeStringResource(table = "NwcWalletConnection", key = "add_wallet_uri_label")
        ),
        uriPlaceholder = nativeString(
            NativeStringResource(table = "NwcWalletConnection", key = "add_wallet_uri_placeholder")
        ),
        paste = nativeString(
            NativeStringResource(table = "NwcWalletConnection", key = "add_wallet_uri_paste")
        ),
        scanInstruction = nativeString(
            NativeStringResource(
                table = "NwcWalletConnection",
                key = "add_wallet_scan_instruction"
            )
        ),
        scanPermission = nativeString(
            NativeStringResource(table = "NwcWalletConnection", key = "add_wallet_scan_permission")
        ),
        scanAllowCamera = nativeString(
            NativeStringResource(
                table = "NwcWalletConnection",
                key = "add_wallet_scan_allow_camera"
            )
        ),
        scanOpenSettings = nativeString(
            NativeStringResource(
                table = "NwcWalletConnection",
                key = "add_wallet_scan_open_settings"
            )
        ),
        scanRestricted = nativeString(
            NativeStringResource(table = "NwcWalletConnection", key = "add_wallet_scan_restricted")
        ),
        scanUnavailable = nativeString(
            NativeStringResource(
                table = "NwcWalletConnection",
                key = "add_wallet_scan_unavailable"
            )
        ),
        confirmTitle = nativeString(
            NativeStringResource(table = "NwcWalletConnection", key = "connect_wallet_title")
        ),
        confirmDescription = nativeString(
            NativeStringResource(table = "NwcWalletConnection", key = "connect_wallet_description")
        ),
        cancel = nativeString(
            NativeStringResource(table = "NwcWalletConnection", key = "connect_wallet_cancel")
        ),
        confirm = nativeString(
            NativeStringResource(table = "NwcWalletConnection", key = "connect_wallet_confirm")
        ),
        aliasLabel = nativeString(
            NativeStringResource(table = "NwcWalletConnection", key = "connect_wallet_alias_label")
        ),
        relayLabel = nativeString(
            NativeStringResource(
                table = "NwcWalletConnection",
                key = "connect_wallet_details_relay"
            )
        ),
        lightningAddressLabel = nativeString(
            NativeStringResource(
                table = "NwcWalletConnection",
                key = "connect_wallet_details_lud16"
            )
        ),
        loading = nativeString(
            NativeStringResource(table = "NwcWalletConnection", key = "connect_wallet_loading")
        ),
        retry = nativeString(
            NativeStringResource(table = "NwcWalletConnection", key = "connect_wallet_retry")
        ),
        warningHeading = nativeString(
            NativeStringResource(
                table = "NwcWalletConnection",
                key = "connect_wallet_warning_heading"
            )
        ),
        warningMissingPayInvoice =
            nativeString(
                NativeStringResource(
                    table = "NwcWalletConnection",
                    key = "connect_wallet_warning_missing_pay_invoice"
                )
            ),
        warningMissingLookupInvoice =
            nativeString(
                NativeStringResource(
                    table = "NwcWalletConnection",
                    key = "connect_wallet_warning_missing_lookup_invoice"
                ),
                appName
            ),
        warningMissingNip44 = nativeString(
            NativeStringResource(
                table = "NwcWalletConnection",
                key = "connect_wallet_warning_missing_nip44"
            )
        ),
        warningLegacyNip04 = nativeString(
            NativeStringResource(
                table = "NwcWalletConnection",
                key = "connect_wallet_warning_legacy_nip04"
            )
        ),
        warningLegacyNip04Default =
            nativeString(
                NativeStringResource(
                    table = "NwcWalletConnection",
                    key = "connect_wallet_warning_legacy_nip04_default"
                )
            ),
        requiredMethods = nativeString(
            NativeStringResource(
                table = "NwcWalletConnection",
                key = "connect_wallet_required_methods"
            ),
            appName
        ),
        errorAlreadyConnected = nativeString(
            NativeStringResource(
                table = "NwcWalletConnection",
                key = "error_wallet_already_connected"
            )
        ),
        errorInvalidUri = nativeString(
            NativeStringResource(table = "NwcWalletConnection", key = "error_invalid_wallet_uri")
        ),
        errorConnection = nativeString(
            NativeStringResource(
                table = "NwcWalletConnection",
                key = "error_relay_connection_failed"
            )
        )
    )
