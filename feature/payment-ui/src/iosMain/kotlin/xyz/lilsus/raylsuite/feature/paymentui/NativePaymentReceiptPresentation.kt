package xyz.lilsus.raylsuite.feature.paymentui

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.ui.format.AmountFormatter
import xyz.lilsus.raylsuite.core.ui.format.currentAmountFormatter
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

data class NativePaymentReceiptContent(
    val title: String,
    val amountLabel: String,
    val amount: String,
    val feeLabel: String,
    val fee: String,
    val feeHint: String?,
    val recipientLabel: String,
    val recipient: String?,
    val domainLabel: String,
    val domain: String?,
    val timestampLabel: String,
    val timestamp: String,
    val preimageLabel: String,
    val preimage: String?,
    val copyTitle: String,
    val copiedTitle: String,
    val explanation: String
)

internal suspend fun PaymentReceiptDetails.toNativeContent(
    feeHint: String?,
    formatter: AmountFormatter = currentAmountFormatter()
): NativePaymentReceiptContent = NativePaymentReceiptContent(
    title = receiptString("result_receipt_title"),
    amountLabel = receiptString("receipt_amount"),
    amount = formatter.formatReceiptAmount(amount, amountSats),
    feeLabel = receiptString("receipt_fee"),
    fee = fee?.let { shown -> feeSats?.let { formatter.formatReceiptAmount(shown, it) } }
        ?: receiptString("receipt_fee_unknown"),
    feeHint = feeHint,
    recipientLabel = receiptString("receipt_recipient"),
    recipient = recipient,
    domainLabel = receiptString("receipt_domain"),
    domain = domain,
    timestampLabel = receiptString("receipt_timestamp"),
    timestamp = formatReceiptTimestamp(createdAtMs),
    preimageLabel = receiptString("receipt_preimage"),
    preimage = preimage?.trim()?.takeIf(String::isNotEmpty),
    copyTitle = receiptString("receipt_copy_preimage"),
    copiedTitle = receiptString("receipt_copied"),
    explanation =
        receiptString("result_receipt_body_prefix") +
            receiptString("result_receipt_body_preimage") +
            receiptString("result_receipt_body_middle") +
            receiptString("result_receipt_body_only") +
            receiptString("result_receipt_body_suffix")
)

private fun AmountFormatter.formatReceiptAmount(
    amount: DisplayAmount,
    sats: DisplayAmount
): String {
    val satoshiAmount = format(sats).removeSuffix(" sat") + " SAT"
    return when (amount.currency) {
        DisplayCurrency.Satoshi -> satoshiAmount
        is DisplayCurrency.Fiat -> "${format(amount)} ($satoshiAmount)"
    }
}

private fun formatReceiptTimestamp(epochMillis: Long): String = NSDateFormatter().apply {
    locale = NSLocale(localeIdentifier = "en_US_POSIX")
    dateFormat = "yyyy-MM-dd HH:mm"
}.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1_000.0))

private suspend fun receiptString(key: String): String =
    nativeString(NativeStringResource(table = "PaymentUI", key = key))
