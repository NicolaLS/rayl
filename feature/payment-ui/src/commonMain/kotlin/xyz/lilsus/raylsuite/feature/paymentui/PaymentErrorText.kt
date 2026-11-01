package xyz.lilsus.raylsuite.feature.paymentui

import xyz.lilsus.raylsuite.core.ui.resources.LocalizedText
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedTextKey
import xyz.lilsus.raylsuite.core.ui.resources.localizedTextWithOptionalDetail

enum class PaymentUiTextKey(override val key: String) : LocalizedTextKey {
    ErrorExchangeRateUnavailable("error_exchange_rate_unavailable"),
    ErrorInvalidInvoice("error_invalid_invoice"),
    ErrorInvalidInvoiceWithDetails("error_invalid_invoice_with_details"),
    ErrorLnurl("error_lnurl"),
    ErrorLnurlWithDetails("error_lnurl_with_details"),
    ErrorNetworkUnavailable("error_network_unavailable"),
    ErrorUnexpectedGeneric("error_unexpected_generic"),
    ErrorUnexpectedWithDetails("error_unexpected_with_details"),
    ToastBitcoinAddress("toast_bitcoin_address"),
    ToastBolt12NotSupported("toast_bolt12_not_supported"),
    ToastLnurlRequestNotSupported("toast_lnurl_request_not_supported"),
    TransactionFee("transaction_fee"),
    TransactionStatusAlreadyPaid("transaction_status_already_paid"),
    TransactionStatusFailure("transaction_status_failure"),
    TransactionStatusSuccess("transaction_status_success");

    override val table: String = "PaymentUI"
}

fun invalidInvoicePaymentErrorText(reason: String?): LocalizedText =
    localizedTextWithOptionalDetail(
        detail = reason,
        generic = PaymentUiTextKey.ErrorInvalidInvoice,
        withDetails = PaymentUiTextKey.ErrorInvalidInvoiceWithDetails
    )

fun lnurlPaymentErrorText(reason: String?): LocalizedText = localizedTextWithOptionalDetail(
    detail = reason,
    generic = PaymentUiTextKey.ErrorLnurl,
    withDetails = PaymentUiTextKey.ErrorLnurlWithDetails
)

fun networkUnavailablePaymentErrorText(): LocalizedText =
    LocalizedText(PaymentUiTextKey.ErrorNetworkUnavailable)

fun exchangeRateUnavailablePaymentErrorText(currencyCode: String): LocalizedText =
    LocalizedText(PaymentUiTextKey.ErrorExchangeRateUnavailable, currencyCode)

fun unexpectedPaymentErrorText(detail: String?): LocalizedText = localizedTextWithOptionalDetail(
    detail = detail,
    generic = PaymentUiTextKey.ErrorUnexpectedGeneric,
    withDetails = PaymentUiTextKey.ErrorUnexpectedWithDetails
)
