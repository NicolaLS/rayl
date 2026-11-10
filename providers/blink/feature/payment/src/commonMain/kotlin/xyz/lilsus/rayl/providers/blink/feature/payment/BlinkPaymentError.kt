package xyz.lilsus.rayl.providers.blink.feature.payment

import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkApiError
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkErrorType
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkWalletCurrency
import xyz.lilsus.raylsuite.core.raylapi.TelemetryPaymentError
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedText
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedTextKey
import xyz.lilsus.raylsuite.core.ui.resources.localizedTextWithOptionalDetail
import xyz.lilsus.raylsuite.feature.paymentflow.ProviderPaymentError

internal enum class BlinkPaymentTextKey(override val key: String) : LocalizedTextKey {
    ConfirmPaymentFundingBitcoin("confirm_payment_funding_bitcoin"),
    ConfirmPaymentFundingStablesats("confirm_payment_funding_stablesats"),
    ErrorBlinkAmountTooSmall("error_blink_amount_too_small"),
    ErrorBlinkInsufficientBalance("error_blink_insufficient_balance"),
    ErrorBlinkInvalidApiKey("error_blink_invalid_api_key"),
    ErrorBlinkInvalidInvoice("error_blink_invalid_invoice"),
    ErrorBlinkInvoiceExpired("error_blink_invoice_expired"),
    ErrorBlinkLimitExceeded("error_blink_limit_exceeded"),
    ErrorBlinkPermissionDenied("error_blink_permission_denied"),
    ErrorBlinkRateLimited("error_blink_rate_limited"),
    ErrorBlinkRouteNotFound("error_blink_route_not_found"),
    ErrorBlinkSelfPayment("error_blink_self_payment"),
    ErrorFundingWalletUnavailable("error_funding_wallet_unavailable"),
    ErrorMissingWalletConnection("error_missing_wallet_connection"),
    ErrorNetworkUnavailable("error_network_unavailable"),
    ErrorPaymentStatusUnknown("error_payment_status_unknown"),
    ErrorPaymentRejectedGeneric("error_payment_rejected_generic"),
    ErrorPaymentRejectedMessage("error_payment_rejected_message"),
    ErrorPaymentUnconfirmed("error_payment_unconfirmed"),
    ErrorPaymentUnconfirmedMessage("error_payment_unconfirmed_message");

    override val table: String = "BlinkPayment"
}

sealed interface BlinkPaymentError : ProviderPaymentError {
    data class Api(val error: BlinkApiError) : BlinkPaymentError

    /** Blink could not say whether the payment went through. */
    data object StatusUnknown : BlinkPaymentError

    override val telemetryError: TelemetryPaymentError
        get() = when (this) {
            is Api -> when (error) {
                BlinkApiError.MissingWalletConnection,
                BlinkApiError.FundingWalletUnavailable -> TelemetryPaymentError.WalletNotReady

                BlinkApiError.NetworkUnavailable,
                BlinkApiError.Timeout -> TelemetryPaymentError.Network

                is BlinkApiError.PaymentRejected,
                is BlinkApiError.BlinkError -> TelemetryPaymentError.RejectedByWallet

                is BlinkApiError.Unexpected -> TelemetryPaymentError.Unexpected
            }

            StatusUnknown -> TelemetryPaymentError.OutcomeUnknown
        }

    override fun toLocalizedText(): LocalizedText = when (this) {
        is Api -> error.toLocalizedText()
        StatusUnknown -> LocalizedText(BlinkPaymentTextKey.ErrorPaymentStatusUnknown)
    }
}

internal fun BlinkWalletCurrency.fundingSourceText(): LocalizedText = LocalizedText(
    when (this) {
        BlinkWalletCurrency.BTC -> BlinkPaymentTextKey.ConfirmPaymentFundingBitcoin
        BlinkWalletCurrency.USD -> BlinkPaymentTextKey.ConfirmPaymentFundingStablesats
    }
)

private fun BlinkApiError.toLocalizedText(): LocalizedText = when (this) {
    BlinkApiError.FundingWalletUnavailable ->
        LocalizedText(BlinkPaymentTextKey.ErrorFundingWalletUnavailable)

    BlinkApiError.MissingWalletConnection ->
        LocalizedText(BlinkPaymentTextKey.ErrorMissingWalletConnection)

    BlinkApiError.NetworkUnavailable ->
        LocalizedText(BlinkPaymentTextKey.ErrorNetworkUnavailable)

    BlinkApiError.Timeout ->
        LocalizedText(BlinkPaymentTextKey.ErrorPaymentUnconfirmed)

    is BlinkApiError.PaymentRejected ->
        code?.toBlinkErrorType()?.toLocalizedText()
            ?: localizedTextWithOptionalDetail(
                detail = message,
                generic = BlinkPaymentTextKey.ErrorPaymentRejectedGeneric,
                withDetails = BlinkPaymentTextKey.ErrorPaymentRejectedMessage
            )

    is BlinkApiError.Unexpected ->
        localizedTextWithOptionalDetail(
            detail = message,
            generic = BlinkPaymentTextKey.ErrorPaymentUnconfirmed,
            withDetails = BlinkPaymentTextKey.ErrorPaymentUnconfirmedMessage
        )

    is BlinkApiError.BlinkError -> type.toLocalizedText()
}

private fun BlinkErrorType.toLocalizedText(): LocalizedText = LocalizedText(
    when (this) {
        BlinkErrorType.PermissionDenied -> BlinkPaymentTextKey.ErrorBlinkPermissionDenied
        BlinkErrorType.InsufficientBalance -> BlinkPaymentTextKey.ErrorBlinkInsufficientBalance
        BlinkErrorType.RouteNotFound -> BlinkPaymentTextKey.ErrorBlinkRouteNotFound
        BlinkErrorType.InvoiceExpired -> BlinkPaymentTextKey.ErrorBlinkInvoiceExpired
        BlinkErrorType.SelfPayment -> BlinkPaymentTextKey.ErrorBlinkSelfPayment
        BlinkErrorType.InvalidInvoice -> BlinkPaymentTextKey.ErrorBlinkInvalidInvoice
        BlinkErrorType.AmountTooSmall -> BlinkPaymentTextKey.ErrorBlinkAmountTooSmall
        BlinkErrorType.LimitExceeded -> BlinkPaymentTextKey.ErrorBlinkLimitExceeded
        BlinkErrorType.RateLimited -> BlinkPaymentTextKey.ErrorBlinkRateLimited
        BlinkErrorType.InvalidApiKey -> BlinkPaymentTextKey.ErrorBlinkInvalidApiKey
    }
)

private fun String.toBlinkErrorType(): BlinkErrorType? =
    BlinkErrorType.entries.firstOrNull { it.name == this }
