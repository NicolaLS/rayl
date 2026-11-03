package xyz.lilsus.raylsuite.feature.paymentflow

import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentCurrencyManager
import xyz.lilsus.raylsuite.feature.paymentui.PaymentReceiptDetails

/** Resolved receipts for this process only. In-flight or unconfirmed payments are never history. */
internal class PaymentHistory(private val currencyManager: PaymentCurrencyManager) {
    private val receipts = ArrayDeque<PaymentReceipt>()
    private var nextId = 0L
    private val mutableDisplayItems = MutableStateFlow<List<SessionTransactionItem>>(emptyList())
    val displayItems = mutableDisplayItems.asStateFlow()

    fun record(
        paymentHash: String,
        amountMsats: Long,
        result: CompletedPayment? = null,
        error: PaymentUiError? = null,
        recipient: String? = null,
        domain: String? = null
    ) {
        require((result == null) != (error == null))
        receipts.addFirst(
            PaymentReceipt(
                id = "payment-${nextId++}",
                paymentHash = paymentHash,
                amountMsats = amountMsats,
                createdAtMs = Clock.System.now().toEpochMilliseconds(),
                result = result,
                error = error,
                recipient = recipient,
                domain = domain
            )
        )
        if (receipts.size > MAX_RECENT_PAYMENTS) receipts.removeLast()
        refreshDisplayItems()
    }

    fun findPaidByPaymentHash(paymentHash: String): SessionTransactionItem? {
        val receipt = receipts.firstOrNull { it.paymentHash == paymentHash && it.result != null }
            ?: return null
        return displayItems.value.firstOrNull { it.id == receipt.id }
    }

    fun refreshDisplayItems() {
        val currency = currencyManager.state.value
        mutableDisplayItems.value = receipts.map { receipt ->
            val result = receipt.result
            SessionTransactionItem(
                id = receipt.id,
                amount = currencyManager.convertMsatsToDisplay(receipt.amountMsats, currency),
                status = when {
                    result == null -> TransactionStatus.Failure
                    result.wasAlreadyPaid -> TransactionStatus.AlreadyPaid
                    else -> TransactionStatus.Success
                },
                createdAtMs = receipt.createdAtMs,
                resultAmount = result?.let {
                    currencyManager.convertMsatsToDisplay(it.amountMsats, currency)
                },
                fee = result?.feeMsats?.let { currencyManager.convertMsatsToDisplay(it, currency) },
                error = receipt.error,
                wasAlreadyPaid = result?.wasAlreadyPaid == true,
                receipt = result?.takeUnless { it.wasAlreadyPaid }?.let {
                    PaymentReceiptDetails(
                        amount = currencyManager.convertMsatsToDisplay(
                            receipt.amountMsats,
                            currency
                        ),
                        fee = it.feeMsats?.let { fee ->
                            currencyManager.convertMsatsToDisplay(fee, currency)
                        },
                        amountSats = DisplayAmount(
                            receipt.amountMsats / MSATS_PER_SAT,
                            DisplayCurrency.Satoshi
                        ),
                        feeSats = it.feeMsats?.let { fee ->
                            DisplayAmount(fee / MSATS_PER_SAT, DisplayCurrency.Satoshi)
                        },
                        createdAtMs = receipt.createdAtMs,
                        recipient = receipt.recipient,
                        domain = receipt.domain,
                        preimage = it.preimage
                    )
                }
            )
        }
    }

    fun reset() {
        receipts.clear()
        nextId = 0
        mutableDisplayItems.value = emptyList()
    }

    private companion object {
        const val MAX_RECENT_PAYMENTS = 10
        const val MSATS_PER_SAT = 1_000L
    }
}

private data class PaymentReceipt(
    val id: String,
    val paymentHash: String,
    val amountMsats: Long,
    val createdAtMs: Long,
    val result: CompletedPayment?,
    val error: PaymentUiError?,
    val recipient: String?,
    val domain: String?
)
