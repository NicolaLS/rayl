package xyz.lilsus.raylsuite.feature.paymentui

import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountKey

/** Why a scanned or entered input was turned away without leaving the scanner. */
sealed interface PaymentToastMessage {
    /** Readable, but nothing Rayl can pay, such as a website link. */
    data object NotLightning : PaymentToastMessage

    data object BitcoinAddressNotSupported : PaymentToastMessage

    data object Bolt12NotSupported : PaymentToastMessage

    data object LnurlRequestNotSupported : PaymentToastMessage
}

/**
 * Text the Scan screen shows briefly. [rejectsInput] marks a toast for an input that was turned
 * away; the hero answers it, because a scanner without a viewfinder has no other way to show it
 * saw the code.
 */
data class PaymentMessage(val text: String, val rejectsInput: Boolean = false)

sealed interface PaymentIntent {
    /**
     * The codes the scanner saw come into view together, most prominent first. The first one
     * that can be paid is the payment: an invoice counts, the larger menu link beside it not.
     */
    data class QrCodeScanned(val rawValues: List<String>) : PaymentIntent {
        constructor(rawValue: String) : this(listOf(rawValue))
    }

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
