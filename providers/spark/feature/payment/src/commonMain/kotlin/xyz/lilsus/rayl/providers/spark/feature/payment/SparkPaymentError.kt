package xyz.lilsus.rayl.providers.spark.feature.payment

import xyz.lilsus.raylsuite.core.ui.resources.LocalizedText
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedTextKey
import xyz.lilsus.raylsuite.core.ui.resources.localizedTextWithOptionalDetail
import xyz.lilsus.raylsuite.feature.paymentflow.ProviderPaymentError

internal enum class SparkPaymentTextKey(override val key: String) : LocalizedTextKey {
    ErrorInsufficientFunds("error_insufficient_funds"),
    ErrorWalletNotReady("error_wallet_not_ready"),
    ErrorSubSatoshiAmount("error_sub_satoshi_amount"),
    ErrorInvoiceAlreadyPaid("error_invoice_already_paid"),
    ErrorInvoiceExpired("error_invoice_expired"),
    ErrorBalanceSettling("error_balance_settling"),
    ErrorPaymentRejectedGeneric("error_payment_rejected_generic"),
    ErrorPaymentRejectedMessage("error_payment_rejected_message"),
    ErrorPaymentUnconfirmed("error_payment_unconfirmed"),
    ErrorPaymentUnconfirmedMessage("error_payment_unconfirmed_message");

    override val table: String = "SparkPayment"
}

sealed interface SparkPaymentError : ProviderPaymentError {
    data object InsufficientFunds : SparkPaymentError

    data object WalletNotReady : SparkPaymentError

    data object SubSatoshiAmount : SparkPaymentError

    data object AlreadyPaid : SparkPaymentError

    data object InvoiceExpired : SparkPaymentError

    data object BalanceSettling : SparkPaymentError

    data class Rejected(val detail: String?) : SparkPaymentError

    data class OutcomeUnknown(val detail: String?) : SparkPaymentError

    override fun toLocalizedText(): LocalizedText = when (this) {
        InsufficientFunds -> LocalizedText(SparkPaymentTextKey.ErrorInsufficientFunds)

        WalletNotReady -> LocalizedText(SparkPaymentTextKey.ErrorWalletNotReady)

        SubSatoshiAmount -> LocalizedText(SparkPaymentTextKey.ErrorSubSatoshiAmount)

        AlreadyPaid -> LocalizedText(SparkPaymentTextKey.ErrorInvoiceAlreadyPaid)

        InvoiceExpired -> LocalizedText(SparkPaymentTextKey.ErrorInvoiceExpired)

        BalanceSettling -> LocalizedText(SparkPaymentTextKey.ErrorBalanceSettling)

        is Rejected -> localizedTextWithOptionalDetail(
            detail,
            SparkPaymentTextKey.ErrorPaymentRejectedGeneric,
            SparkPaymentTextKey.ErrorPaymentRejectedMessage
        )

        is OutcomeUnknown -> localizedTextWithOptionalDetail(
            detail,
            SparkPaymentTextKey.ErrorPaymentUnconfirmed,
            SparkPaymentTextKey.ErrorPaymentUnconfirmedMessage
        )
    }
}
