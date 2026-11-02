package xyz.lilsus.rayl.providers.rayl.feature.payment

import xyz.lilsus.raylsuite.core.ui.resources.LocalizedTextKey

/** Resolves Rayl payment text for the shared payment flow on Android. */
fun raylPaymentStringResource(key: LocalizedTextKey): Int = when (key) {
    is RaylPaymentTextKey -> when (key) {
        RaylPaymentTextKey.ErrorInsufficientFunds -> R.string.error_insufficient_funds

        RaylPaymentTextKey.ErrorPocketNotReady -> R.string.error_pocket_not_ready

        RaylPaymentTextKey.ErrorSubSatoshiAmount -> R.string.error_sub_satoshi_amount

        RaylPaymentTextKey.ErrorInvoiceExpired -> R.string.error_invoice_expired

        RaylPaymentTextKey.ErrorBalanceSettling -> R.string.error_balance_settling

        RaylPaymentTextKey.ErrorPaymentRejectedGeneric -> R.string.error_payment_rejected_generic

        RaylPaymentTextKey.ErrorPaymentRejectedMessage -> R.string.error_payment_rejected_message

        RaylPaymentTextKey.ErrorPaymentUnconfirmed -> R.string.error_payment_unconfirmed

        RaylPaymentTextKey.ErrorPaymentUnconfirmedMessage ->
            R.string.error_payment_unconfirmed_message
    }

    else -> error("Unknown Rayl payment text key: ${key.table}.${key.key}")
}
