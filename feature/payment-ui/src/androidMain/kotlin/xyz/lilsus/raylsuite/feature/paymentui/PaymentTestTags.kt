package xyz.lilsus.raylsuite.feature.paymentui

object PaymentTestTags {
    const val MANUAL_AMOUNT_SWITCH_CURRENCY = "payment_manual_amount_switch_currency"
    const val MANUAL_AMOUNT_EQUIVALENT = "payment_manual_amount_equivalent"
    const val SCREEN = "payment_screen"
    const val ACTIVE_CONTENT = "payment_active_content"
    const val SETTINGS_BUTTON = "payment_settings_button"
    const val SESSION_TRANSACTIONS_BUTTON = "payment_session_transactions_button"
    const val SCANNER_CLOSE_BUTTON = "payment_scanner_close_button"

    const val CONFIRMATION_SHEET = "payment_confirmation_sheet"
    const val CONFIRMATION_PAY_BUTTON = "payment_confirmation_pay_button"
    const val CONFIRMATION_DISMISS_BUTTON = "payment_confirmation_dismiss_button"
    const val LNURL_PAY_DETAILS = "payment_lnurl_pay_details"

    const val MANUAL_AMOUNT_SCREEN = "payment_manual_amount_screen"
    const val MANUAL_AMOUNT_DISPLAY = "payment_manual_amount_display"
    const val MANUAL_AMOUNT_CONTINUE_BUTTON = "payment_manual_amount_continue_button"
    const val MANUAL_AMOUNT_REVIEW = "payment_manual_amount_review"
    const val MANUAL_AMOUNT_REVIEW_EQUIVALENT = "payment_manual_amount_review_equivalent"
    const val MANUAL_AMOUNT_BACK_BUTTON = "payment_manual_amount_back_button"
    const val MANUAL_AMOUNT_CANCEL_BUTTON = "payment_manual_amount_cancel_button"
    const val MANUAL_AMOUNT_PAY_BUTTON = "payment_manual_amount_pay_button"
    const val MANUAL_AMOUNT_DECIMAL_KEY = "payment_manual_amount_key_decimal"
    const val MANUAL_AMOUNT_BACKSPACE_KEY = "payment_manual_amount_key_backspace"

    const val RESULT = "payment_result"
    const val RESULT_SUCCESS = "payment_result_success"
    const val RESULT_ALREADY_PAID = "payment_result_already_paid"
    const val RESULT_ERROR = "payment_result_error"
    const val RECEIPT = "payment_receipt"
    const val RECEIPT_COPY_PREIMAGE = "payment_receipt_copy_preimage"

    fun manualAmountDigitKey(value: Int): String = "payment_manual_amount_key_$value"
}
