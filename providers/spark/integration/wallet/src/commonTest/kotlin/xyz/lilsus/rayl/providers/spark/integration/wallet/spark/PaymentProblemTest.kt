package xyz.lilsus.rayl.providers.spark.integration.wallet.spark

import breez_sdk_spark.SdkException
import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.lilsus.rayl.providers.spark.application.payment.SparkPaymentProblem

class PaymentProblemTest {
    @Test
    fun recognizesTheRefusalsBreezDocumentsByTypeOrText() {
        assertEquals(SparkPaymentProblem.Network, SdkException.NetworkException("offline").paymentProblem())
        assertEquals(
            SparkPaymentProblem.AlreadyPaid,
            SdkException.SparkException("grpc: status: AlreadyExists, message: paid").paymentProblem()
        )
        assertEquals(
            SparkPaymentProblem.Expired,
            SdkException.InvalidInput("Invoice has expired").paymentProblem()
        )
        assertEquals(
            SparkPaymentProblem.BalanceSettling,
            SdkException.SparkException("Failed to select leaves").paymentProblem()
        )
        assertEquals(SparkPaymentProblem.Other, SdkException.Generic("boom").paymentProblem())
    }
}
