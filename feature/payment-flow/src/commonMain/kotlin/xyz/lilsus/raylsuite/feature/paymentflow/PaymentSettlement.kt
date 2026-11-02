package xyz.lilsus.raylsuite.feature.paymentflow

import xyz.lilsus.raylsuite.core.ui.resources.LocalizedText

/**
 * The wallet-owned half of a payment. The shared flow admits input, resolves invoices, confirms,
 * and records results; a provider decides how an admitted invoice is funded and submits it.
 */
interface PaymentSettlement<P : SettleablePayment> {
    /** Binds an admitted invoice to the wallet's funding before confirmation. */
    suspend fun prepare(request: PreparedPayment): SettlementPreparation<P>

    /** Submits [payment]. Failures to reach a verdict are [SettlementOutcome.Unknown], not thrown. */
    suspend fun settle(payment: P): SettlementOutcome

    /** Maps an unexpected failure during admission or settlement to what the user sees. */
    fun errorFor(cause: Throwable): PaymentUiError = PaymentUiError.Unexpected(cause.message)
}

sealed interface SettlementPreparation<out P> {
    data class Ready<P>(val payment: P, val fundingSource: LocalizedText? = null) :
        SettlementPreparation<P>

    data class Unavailable(val error: PaymentUiError) : SettlementPreparation<Nothing>

    /** The wallet says the invoice is already paid, so there is nothing left to pay. */
    data object AlreadyPaid : SettlementPreparation<Nothing>
}

sealed interface SettlementOutcome {
    /** The invoice is paid. [wasAlreadyPaid] means it was settled earlier, not by this attempt. */
    data class Paid(
        val feeMsats: Long,
        val preimage: String?,
        val wasAlreadyPaid: Boolean = false
    ) : SettlementOutcome

    /** The wallet definitively did not pay. */
    data class Failed(val error: PaymentUiError) : SettlementOutcome

    /** The wallet may or may not have paid, so nothing is recorded. */
    data class Unknown(val error: PaymentUiError) : SettlementOutcome
}
