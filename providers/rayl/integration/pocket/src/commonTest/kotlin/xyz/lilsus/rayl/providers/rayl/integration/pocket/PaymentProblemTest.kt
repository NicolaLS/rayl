package xyz.lilsus.rayl.providers.rayl.integration.pocket

import breez_sdk_spark.SdkException
import kotlin.test.Test
import kotlin.test.assertEquals

class PaymentProblemTest {
    @Test
    fun recognizesTheRefusalsBreezDocumentsByTypeOrText() {
        assertEquals(PocketPaymentProblem.Network, SdkException.NetworkException("offline").paymentProblem())
        assertEquals(
            PocketPaymentProblem.AlreadyPaid,
            SdkException.SparkException("grpc: status: AlreadyExists, message: paid").paymentProblem()
        )
        assertEquals(
            PocketPaymentProblem.Expired,
            SdkException.InvalidInput("Invoice has expired").paymentProblem()
        )
        assertEquals(
            PocketPaymentProblem.BalanceSettling,
            SdkException.SparkException("Failed to select leaves").paymentProblem()
        )
        assertEquals(PocketPaymentProblem.Other, SdkException.Generic("boom").paymentProblem())
    }
}
