package xyz.lilsus.raylsuite.feature.paymentui

import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountKey

sealed interface PaymentToastMessage {
    data object BitcoinAddressNotSupported : PaymentToastMessage

    data object Bolt12NotSupported : PaymentToastMessage

    data object LnurlRequestNotSupported : PaymentToastMessage
}

sealed interface PaymentIntent {
    data class QrCodeScanned(val rawValue: String) : PaymentIntent

    /** Raw text typed or pasted into a lens. It uses the app's ordinary parser, never a saved target. */
    data class RawInputSubmitted(val rawValue: String) : PaymentIntent

    data class TransactionDetailNavigationHandled(val id: String) : PaymentIntent

    data object SessionTransactionsOpened : PaymentIntent

    data object DismissResult : PaymentIntent

    data class ManualAmountKeyPress(val key: ManualAmountKey) : PaymentIntent

    data object ManualAmountSubmit : PaymentIntent

    data object ManualAmountDismiss : PaymentIntent

    data object ConfirmPaymentSubmit : PaymentIntent

    data object ConfirmPaymentDismiss : PaymentIntent
}
