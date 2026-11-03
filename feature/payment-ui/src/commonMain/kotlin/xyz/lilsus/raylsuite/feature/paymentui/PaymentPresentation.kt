package xyz.lilsus.raylsuite.feature.paymentui

import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.ui.hero.RaylHeroPhase
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountUiState

sealed interface PaymentScreenState {
    data object Active : PaymentScreenState

    data class Loading(val kind: PaymentLoadingKind) : PaymentScreenState

    data class EnterAmount(
        val entry: ManualAmountUiState,
        val lnurlPayDisplay: LnurlPayDisplay? = null
    ) : PaymentScreenState

    data class Confirm(
        val amount: PaymentConfirmationAmount,
        val lnurlPayDisplay: LnurlPayDisplay? = null,
        val fundingSource: String? = null
    ) : PaymentScreenState

    data class Success(
        val amountPaid: DisplayAmount,
        /** Null when the wallet didn't report the fee. */
        val feePaid: DisplayAmount?,
        val wasAlreadyPaid: Boolean
    ) : PaymentScreenState

    data class Error(val message: String) : PaymentScreenState
}

enum class PaymentLoadingKind {
    Resolving,
    Paying
}

data class PaymentConfirmationAmount(
    val primary: DisplayAmount,
    val exactSats: DisplayAmount? = null,
    val primaryIsEstimate: Boolean = false
)

data class PaymentSessionTransaction(
    val id: String,
    val amount: DisplayAmount,
    val statusLabel: String,
    val statusTone: PaymentStatusTone,
    val createdAtMs: Long,
    val supportingText: String?
)

/** Provider-owned history projected into the values a native receipt renders. */
data class PaymentReceiptDetails(
    val amount: DisplayAmount,
    /** Null, like [feeSats], when the wallet didn't report the fee. */
    val fee: DisplayAmount?,
    val amountSats: DisplayAmount,
    val feeSats: DisplayAmount?,
    val createdAtMs: Long,
    val recipient: String? = null,
    val domain: String? = null,
    val preimage: String? = null
)

data class PaymentTransactionDetail(
    val id: String,
    val state: PaymentScreenState,
    val receipt: PaymentReceiptDetails? = null
) {
    init {
        require(state is PaymentScreenState.Success || state is PaymentScreenState.Error) {
            "Recent payments must have a resolved result"
        }
    }
}

data class PaymentSessionItem(
    val transaction: PaymentSessionTransaction,
    val detail: PaymentTransactionDetail
) {
    val id: String = transaction.id

    init {
        require(detail.id == id) {
            "Payment session projections must share an ID"
        }
    }
}

data class PaymentFlowState(
    val payment: PaymentScreenState,
    val sessionItems: List<PaymentSessionItem>,
    val newSessionTransactionCount: Int = 0,
    val transactionDetailNavigationTarget: String? = null
)

enum class PaymentStatusTone {
    Success,
    Failure
}

fun PaymentScreenState.toHeroPhase(): RaylHeroPhase = when (this) {
    PaymentScreenState.Active -> RaylHeroPhase.Ready

    is PaymentScreenState.Confirm,
    is PaymentScreenState.EnterAmount -> RaylHeroPhase.Acknowledged

    is PaymentScreenState.Loading ->
        if (kind == PaymentLoadingKind.Resolving) {
            RaylHeroPhase.Acknowledged
        } else {
            RaylHeroPhase.Processing
        }

    is PaymentScreenState.Success -> RaylHeroPhase.Succeeded

    is PaymentScreenState.Error -> RaylHeroPhase.Failed
}
