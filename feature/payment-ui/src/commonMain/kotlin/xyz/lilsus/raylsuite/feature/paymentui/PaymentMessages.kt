package xyz.lilsus.raylsuite.feature.paymentui

import xyz.lilsus.raylsuite.core.ui.resources.LocalizedText

fun PaymentToastMessage.localizedText(): LocalizedText = LocalizedText(textKey())

internal fun PaymentToastMessage.textKey(): PaymentUiTextKey = when (this) {
    PaymentToastMessage.BitcoinAddressNotSupported ->
        PaymentUiTextKey.ToastBitcoinAddress

    PaymentToastMessage.Bolt12NotSupported ->
        PaymentUiTextKey.ToastBolt12NotSupported

    PaymentToastMessage.LnurlRequestNotSupported ->
        PaymentUiTextKey.ToastLnurlRequestNotSupported
}
