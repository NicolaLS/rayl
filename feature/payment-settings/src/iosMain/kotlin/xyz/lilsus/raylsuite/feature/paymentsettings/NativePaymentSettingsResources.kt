package xyz.lilsus.raylsuite.feature.paymentsettings

import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

suspend fun nativePaymentSettingsStrings(): Map<String, String> = mapOf(
    "confirmTitle" to
        nativeString(
            NativeStringResource(table = "PaymentSettings", key = "settings_payments_confirm_label")
        ),
    "always" to
        nativeString(
            NativeStringResource(table = "PaymentSettings", key = "settings_payments_option_always")
        ),
    "above" to
        nativeString(
            NativeStringResource(table = "PaymentSettings", key = "settings_payments_option_above")
        ),
    "hapticsScan" to
        nativeString(
            NativeStringResource(table = "PaymentSettings", key = "settings_payments_haptics_scan")
        ),
    "hapticsPayment" to
        nativeString(
            NativeStringResource(
                table = "PaymentSettings",
                key = "settings_payments_haptics_payment"
            )
        ),
    "offerSaveTargets" to
        nativeString(
            NativeStringResource(
                table = "PaymentSettings",
                key = "settings_payments_offer_save_targets"
            )
        ),
    "offerSaveTargetsSubtitle" to
        nativeString(
            NativeStringResource(
                table = "PaymentSettings",
                key = "settings_payments_offer_save_targets_subtitle"
            )
        )
)
