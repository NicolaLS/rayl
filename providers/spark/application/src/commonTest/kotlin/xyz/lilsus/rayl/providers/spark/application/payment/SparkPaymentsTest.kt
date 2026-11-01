package xyz.lilsus.rayl.providers.spark.application.payment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class SparkPaymentsTest {
    @Test
    fun quotesNeedAnAttachedSession() = runTest {
        val payments = SparkPayments()

        assertEquals(SparkQuoteResult.NotReady, payments.quote("lnbcrt1", null))
    }

    @Test
    fun paysThroughTheSessionThatQuoted() = runTest {
        val client = FakeClient()
        val payments = SparkPayments().apply { attach(client) }
        val quoted = assertIs<SparkQuoteResult.Quoted>(payments.quote("lnbcrt1", 21))

        assertEquals(2, quoted.quote.feeSats)
        assertEquals(SparkPayOutcome.Paid(2, null), payments.pay(quoted.quote))
        assertEquals(1, client.payments)
    }

    @Test
    fun aQuoteFromAnEarlierSessionIsNeverSubmitted() = runTest {
        val first = FakeClient()
        val second = FakeClient()
        val payments = SparkPayments().apply { attach(first) }
        val quoted = assertIs<SparkQuoteResult.Quoted>(payments.quote("lnbcrt1", null))

        payments.detach()
        payments.attach(second)

        assertIs<SparkPayOutcome.Failed>(payments.pay(quoted.quote))
        assertEquals(0, first.payments + second.payments)
    }
}

private class FakeClient : SparkPaymentClient {
    var payments = 0

    override suspend fun quote(invoice: String, amountSats: Long?): SparkQuoteResult = SparkQuoteResult.Quoted(
        object : SparkQuote {
            override val feeSats = 2L
        }
    )

    override suspend fun pay(quote: SparkQuote): SparkPayOutcome {
        payments++
        return SparkPayOutcome.Paid(quote.feeSats, null)
    }
}
