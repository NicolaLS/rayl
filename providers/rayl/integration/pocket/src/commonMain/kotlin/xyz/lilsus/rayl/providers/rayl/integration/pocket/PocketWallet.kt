package xyz.lilsus.rayl.providers.rayl.integration.pocket

import kotlinx.coroutines.flow.Flow

/** A connected Spark wallet session behind the pocket. */
internal interface PocketWallet {
    val events: Flow<PocketEvent>

    suspend fun balanceSats(): Long

    suspend fun quote(invoice: String, amountSats: Long?): PocketQuoteResult

    suspend fun pay(quote: PocketQuote): PocketPayOutcome

    /** A Lightning invoice paying into this wallet; `null` lets the payer choose the amount. */
    suspend fun createInvoice(amountSats: Long?, description: String): String

    suspend fun disconnect()
}

internal fun interface PocketConnector {
    suspend fun connect(key: PocketKey): PocketWallet
}

internal sealed interface PocketEvent {
    /** Balance or payment state may have changed. */
    data object Changed : PocketEvent

    data class Received(val invoice: String, val amountSats: Long) : PocketEvent
}

/** What paying from the pocket needs, so payment features stay independent of its lifecycle. */
interface PocketPayments {
    suspend fun quote(invoice: String, amountSats: Long?): PocketQuoteResult

    suspend fun pay(quote: PocketQuote): PocketPayOutcome
}

/** A funded, fee-quoted payment that has not been submitted. */
interface PocketQuote {
    val feeSats: Long
}

/** Why the pocket refused a payment, as far as the SDK says. */
enum class PocketPaymentProblem {
    InsufficientFunds,
    AlreadyPaid,
    Expired,

    /** Funds are briefly reserved, e.g. after an interrupted leaf swap. */
    BalanceSettling,
    Network,
    Other
}

sealed interface PocketQuoteResult {
    data class Quoted(val quote: PocketQuote) : PocketQuoteResult

    data object InsufficientFunds : PocketQuoteResult

    data object NotReady : PocketQuoteResult

    data class Rejected(val problem: PocketPaymentProblem, val detail: String?) : PocketQuoteResult
}

sealed interface PocketPayOutcome {
    data class Paid(val feeSats: Long, val preimage: String?) : PocketPayOutcome

    /** The wallet definitively did not pay. */
    data class Failed(val problem: PocketPaymentProblem, val detail: String?) : PocketPayOutcome

    /** The payment may still complete; callers must not treat it as failed. */
    data class Unknown(val detail: String?) : PocketPayOutcome
}
