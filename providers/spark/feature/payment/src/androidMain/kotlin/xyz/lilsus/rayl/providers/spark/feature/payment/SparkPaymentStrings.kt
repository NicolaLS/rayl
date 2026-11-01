package xyz.lilsus.rayl.providers.spark.feature.payment

import xyz.lilsus.raylsuite.core.ui.resources.LocalizedTextKey

/** Resolves Spark payment text for the shared payment flow on Android. */
fun sparkPaymentStringResource(key: LocalizedTextKey): Int = when (key) {
    is SparkPaymentTextKey -> when (key) {
        SparkPaymentTextKey.ErrorInsufficientFunds -> R.string.error_insufficient_funds

        SparkPaymentTextKey.ErrorWalletNotReady -> R.string.error_wallet_not_ready

        SparkPaymentTextKey.ErrorSubSatoshiAmount -> R.string.error_sub_satoshi_amount

        SparkPaymentTextKey.ErrorInvoiceAlreadyPaid -> R.string.error_invoice_already_paid

        SparkPaymentTextKey.ErrorInvoiceExpired -> R.string.error_invoice_expired

        SparkPaymentTextKey.ErrorBalanceSettling -> R.string.error_balance_settling

        SparkPaymentTextKey.ErrorPaymentRejectedGeneric -> R.string.error_payment_rejected_generic

        SparkPaymentTextKey.ErrorPaymentRejectedMessage -> R.string.error_payment_rejected_message

        SparkPaymentTextKey.ErrorPaymentUnconfirmed -> R.string.error_payment_unconfirmed

        SparkPaymentTextKey.ErrorPaymentUnconfirmedMessage ->
            R.string.error_payment_unconfirmed_message
    }

    else -> error("Unknown Spark payment text key: ${key.table}.${key.key}")
}
