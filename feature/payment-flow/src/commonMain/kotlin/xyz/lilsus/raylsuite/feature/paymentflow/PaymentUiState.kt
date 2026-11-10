package xyz.lilsus.raylsuite.feature.paymentflow

import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.raylapi.TelemetryPaymentError
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedText
import xyz.lilsus.raylsuite.feature.paymentui.LnurlPayDisplay
import xyz.lilsus.raylsuite.feature.paymentui.PaymentConfirmationAmount
import xyz.lilsus.raylsuite.feature.paymentui.PaymentReceiptDetails
import xyz.lilsus.raylsuite.feature.paymentui.PaymentUiTextKey
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountUiState

sealed interface PaymentUiState {
    data object Active : PaymentUiState

    data class Loading(val kind: LoadingKind = LoadingKind.Paying) : PaymentUiState

    data class EnterAmount(
        val entry: ManualAmountUiState,
        val lnurlPayDisplay: LnurlPayDisplay? = null
    ) : PaymentUiState

    data class Confirm(
        val amount: PaymentConfirmationAmount,
        val lnurlPayDisplay: LnurlPayDisplay? = null,
        /** Provider-chosen balance the payment is funded from, when the wallet has several. */
        val fundingSource: LocalizedText? = null
    ) : PaymentUiState

    data class Success(
        val amountPaid: DisplayAmount,
        /** Null when the wallet didn't report the fee. */
        val feePaid: DisplayAmount?,
        val wasAlreadyPaid: Boolean = false
    ) : PaymentUiState

    data class Error(val error: PaymentUiError) : PaymentUiState
}

enum class LoadingKind {
    Resolving,
    Paying
}

sealed interface PaymentUiError {
    data class Provider(val error: ProviderPaymentError) : PaymentUiError

    data class InvalidInvoice(val reason: String? = null) : PaymentUiError

    data class Lnurl(val reason: String? = null) : PaymentUiError

    data object NetworkUnavailable : PaymentUiError

    data class ExchangeRateUnavailable(val currencyCode: String) : PaymentUiError

    data class Unexpected(val detail: String? = null) : PaymentUiError
}

/** A wallet-specific failure. The provider owns its wording. */
interface ProviderPaymentError {
    fun toLocalizedText(): LocalizedText

    /** The category usage data reports this failure as; never its text. */
    val telemetryError: TelemetryPaymentError get() = TelemetryPaymentError.Wallet
}

data class SessionTransactionItem(
    val id: String,
    val amount: DisplayAmount,
    val status: TransactionStatus,
    val createdAtMs: Long,
    val resultAmount: DisplayAmount? = null,
    val fee: DisplayAmount? = null,
    val error: PaymentUiError? = null,
    val wasAlreadyPaid: Boolean = false,
    val receipt: PaymentReceiptDetails? = null
)

enum class TransactionStatus {
    Success,
    AlreadyPaid,
    Failure
}

internal fun SessionTransactionItem.toDetailUiState(): PaymentUiState = when (status) {
    TransactionStatus.Success,
    TransactionStatus.AlreadyPaid -> {
        val paidAmount = resultAmount ?: amount
        PaymentUiState.Success(
            amountPaid = paidAmount,
            feePaid = fee,
            wasAlreadyPaid = status == TransactionStatus.AlreadyPaid || wasAlreadyPaid
        )
    }

    TransactionStatus.Failure -> PaymentUiState.Error(error ?: PaymentUiError.Unexpected())
}

internal fun TransactionStatus.labelKey(): PaymentUiTextKey = when (this) {
    TransactionStatus.Success -> PaymentUiTextKey.TransactionStatusSuccess
    TransactionStatus.AlreadyPaid -> PaymentUiTextKey.TransactionStatusAlreadyPaid
    TransactionStatus.Failure -> PaymentUiTextKey.TransactionStatusFailure
}
