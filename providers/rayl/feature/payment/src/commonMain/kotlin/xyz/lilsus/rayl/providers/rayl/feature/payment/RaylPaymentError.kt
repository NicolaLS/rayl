package xyz.lilsus.rayl.providers.rayl.feature.payment

import xyz.lilsus.raylsuite.core.raylapi.TelemetryPaymentError
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedText
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedTextKey
import xyz.lilsus.raylsuite.core.ui.resources.localizedTextWithOptionalDetail
import xyz.lilsus.raylsuite.feature.paymentflow.ProviderPaymentError

internal enum class RaylPaymentTextKey(override val key: String) : LocalizedTextKey {
    ErrorInsufficientFunds("error_insufficient_funds"),
    ErrorPocketNotReady("error_pocket_not_ready"),
    ErrorSubSatoshiAmount("error_sub_satoshi_amount"),
    ErrorInvoiceExpired("error_invoice_expired"),
    ErrorBalanceSettling("error_balance_settling"),
    ErrorPaymentRejectedGeneric("error_payment_rejected_generic"),
    ErrorPaymentRejectedMessage("error_payment_rejected_message"),
    ErrorPaymentUnconfirmed("error_payment_unconfirmed"),
    ErrorPaymentUnconfirmedMessage("error_payment_unconfirmed_message");

    override val table: String = "RaylPayment"
}

sealed interface RaylPaymentError : ProviderPaymentError {
    data object InsufficientFunds : RaylPaymentError

    data object PocketNotReady : RaylPaymentError

    data object SubSatoshiAmount : RaylPaymentError

    data object InvoiceExpired : RaylPaymentError

    data object BalanceSettling : RaylPaymentError

    data class Rejected(val detail: String?) : RaylPaymentError

    data class OutcomeUnknown(val detail: String?) : RaylPaymentError

    override val telemetryError: TelemetryPaymentError
        get() = when (this) {
            InsufficientFunds -> TelemetryPaymentError.InsufficientFunds
            PocketNotReady, BalanceSettling -> TelemetryPaymentError.WalletNotReady
            SubSatoshiAmount -> TelemetryPaymentError.InvalidInvoice
            InvoiceExpired -> TelemetryPaymentError.Expired
            is Rejected -> TelemetryPaymentError.RejectedByWallet
            is OutcomeUnknown -> TelemetryPaymentError.OutcomeUnknown
        }

    override fun toLocalizedText(): LocalizedText = when (this) {
        InsufficientFunds -> LocalizedText(RaylPaymentTextKey.ErrorInsufficientFunds)

        PocketNotReady -> LocalizedText(RaylPaymentTextKey.ErrorPocketNotReady)

        SubSatoshiAmount -> LocalizedText(RaylPaymentTextKey.ErrorSubSatoshiAmount)

        InvoiceExpired -> LocalizedText(RaylPaymentTextKey.ErrorInvoiceExpired)

        BalanceSettling -> LocalizedText(RaylPaymentTextKey.ErrorBalanceSettling)

        is Rejected -> localizedTextWithOptionalDetail(
            detail,
            RaylPaymentTextKey.ErrorPaymentRejectedGeneric,
            RaylPaymentTextKey.ErrorPaymentRejectedMessage
        )

        is OutcomeUnknown -> localizedTextWithOptionalDetail(
            detail,
            RaylPaymentTextKey.ErrorPaymentUnconfirmed,
            RaylPaymentTextKey.ErrorPaymentUnconfirmedMessage
        )
    }
}
