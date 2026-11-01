package xyz.lilsus.raylsuite.feature.paymentui

import android.content.Context
import androidx.annotation.StringRes

fun PaymentToastMessage.localizedMessage(context: Context): String =
    context.getString(textKey().androidStringResource())

@StringRes
fun PaymentUiTextKey.androidStringResource(): Int = when (this) {
    PaymentUiTextKey.ErrorExchangeRateUnavailable ->
        R.string.error_exchange_rate_unavailable

    PaymentUiTextKey.ErrorInvalidInvoice -> R.string.error_invalid_invoice

    PaymentUiTextKey.ErrorInvalidInvoiceWithDetails ->
        R.string.error_invalid_invoice_with_details

    PaymentUiTextKey.ErrorLnurl -> R.string.error_lnurl

    PaymentUiTextKey.ErrorLnurlWithDetails -> R.string.error_lnurl_with_details

    PaymentUiTextKey.ErrorNetworkUnavailable -> R.string.error_network_unavailable

    PaymentUiTextKey.ErrorUnexpectedGeneric -> R.string.error_unexpected_generic

    PaymentUiTextKey.ErrorUnexpectedWithDetails -> R.string.error_unexpected_with_details

    PaymentUiTextKey.ToastBitcoinAddress -> R.string.toast_bitcoin_address

    PaymentUiTextKey.ToastBolt12NotSupported -> R.string.toast_bolt12_not_supported

    PaymentUiTextKey.ToastLnurlRequestNotSupported ->
        R.string.toast_lnurl_request_not_supported

    PaymentUiTextKey.TransactionFee -> R.string.transaction_fee

    PaymentUiTextKey.TransactionStatusAlreadyPaid -> R.string.transaction_status_already_paid

    PaymentUiTextKey.TransactionStatusFailure -> R.string.transaction_status_failure

    PaymentUiTextKey.TransactionStatusSuccess -> R.string.transaction_status_success
}
