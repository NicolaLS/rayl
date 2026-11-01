package xyz.lilsus.rayl.providers.spark.application.payment

import kotlin.concurrent.Volatile

/**
 * Paying from the connected Spark wallet. Only BOLT11 invoices are paid: LNURL and Lightning
 * addresses resolve to one through the shared payment flow first.
 *
 * NOTE: Spark invoices (`spark1…`) are deliberately not payable, matching Rayl's built-in wallet,
 * so Spark can run on the shared payment flow, which admits only Lightning invoices.
 */
interface SparkPaymentClient {
    suspend fun quote(invoice: String, amountSats: Long?): SparkQuoteResult

    suspend fun pay(quote: SparkQuote): SparkPayOutcome
}

/** A funded, fee-quoted payment that has not been submitted. */
interface SparkQuote {
    val feeSats: Long
}

/** Why the wallet refused a payment, as far as the SDK says. */
enum class SparkPaymentProblem {
    InsufficientFunds,
    AlreadyPaid,
    Expired,

    /** Funds are briefly reserved, e.g. after an interrupted leaf swap. */
    BalanceSettling,
    Network,
    Other
}

sealed interface SparkQuoteResult {
    data class Quoted(val quote: SparkQuote) : SparkQuoteResult

    data object InsufficientFunds : SparkQuoteResult

    data object NotReady : SparkQuoteResult

    data class Rejected(val problem: SparkPaymentProblem, val detail: String?) : SparkQuoteResult
}

sealed interface SparkPayOutcome {
    data class Paid(val feeSats: Long, val preimage: String?) : SparkPayOutcome

    /** The wallet definitively did not pay. */
    data class Failed(val problem: SparkPaymentProblem, val detail: String?) : SparkPayOutcome

    /** The payment may still complete; callers must not treat it as failed. */
    data class Unknown(val detail: String?) : SparkPayOutcome
}

interface PaymentSessionLifecycle {
    suspend fun attach(client: SparkPaymentClient)

    suspend fun detach()
}

/** Payments through whichever SDK session [DefaultWalletAccess] currently has attached. */
class SparkPayments : PaymentSessionLifecycle {
    @Volatile private var client: SparkPaymentClient? = null

    override suspend fun attach(client: SparkPaymentClient) {
        this.client = client
    }

    override suspend fun detach() {
        client = null
    }

    suspend fun quote(invoice: String, amountSats: Long?): SparkQuoteResult {
        val current = client ?: return SparkQuoteResult.NotReady
        return when (val result = current.quote(invoice, amountSats)) {
            is SparkQuoteResult.Quoted -> SparkQuoteResult.Quoted(
                SessionQuote(current, result.quote)
            )

            else -> result
        }
    }

    suspend fun pay(quote: SparkQuote): SparkPayOutcome {
        val sessionQuote = quote as SessionQuote
        // A quote belongs to the session that prepared it; nothing was submitted for it yet.
        if (client !== sessionQuote.client) {
            return SparkPayOutcome.Failed(SparkPaymentProblem.Other, "The wallet reconnected")
        }
        return sessionQuote.client.pay(sessionQuote.quote)
    }

    private class SessionQuote(val client: SparkPaymentClient, val quote: SparkQuote) : SparkQuote {
        override val feeSats: Long get() = quote.feeSats
    }
}
