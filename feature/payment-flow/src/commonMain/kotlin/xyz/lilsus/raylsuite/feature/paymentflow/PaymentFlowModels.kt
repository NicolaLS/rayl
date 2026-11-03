package xyz.lilsus.raylsuite.feature.paymentflow

import fr.acinq.lightning.payment.Bolt11Invoice
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentAmountQuote
import xyz.lilsus.raylsuite.feature.paymentui.LnurlPayDisplay

/** What a provider needs to settle an admitted invoice and what the flow records afterwards. */
interface SettleablePayment {
    val invoice: Bolt11Invoice
    val amountOverrideMsats: Long?
    val targetContext: HubTargetContext?
    val lnurlPayDisplay: LnurlPayDisplay?
}

/** An admitted invoice, before any wallet-specific funding decision. */
data class PreparedPayment(
    override val invoice: Bolt11Invoice,
    override val amountOverrideMsats: Long?,
    val isManualEntry: Boolean = false,
    override val targetContext: HubTargetContext? = null,
    override val lnurlPayDisplay: LnurlPayDisplay? = null,
    val paymentQuote: PaymentAmountQuote? = null
) : SettleablePayment

data class CompletedPayment(
    val amountMsats: Long,
    /** Null when the wallet didn't report the fee. */
    val feeMsats: Long?,
    val wasAlreadyPaid: Boolean = false,
    val preimage: String?
)

internal sealed interface AdmissionResult {
    data object Presented : AdmissionResult

    data class Payment(val payment: PreparedPayment) : AdmissionResult
}

internal sealed interface ConfirmationResult<out P> {
    data object Presented : ConfirmationResult<Nothing>

    data class Execute<P>(val payment: P) : ConfirmationResult<P>

    data class AlreadyPaid(val payment: PreparedPayment) : ConfirmationResult<Nothing>
}
