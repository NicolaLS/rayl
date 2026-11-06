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

    @Test
    fun recognizesTheRefusalsTheRealSdkSendsWhenSending() {
        // Captured from Breez SDK 0.27.2 on regtest (wallet integration tests). Unrecognised, they
        // were shown as "payment outcome unknown" (MOB-101).
        assertEquals(
            SparkPaymentProblem.InsufficientFunds,
            SdkException.SparkException("v1=Tree service error: insufficient funds").paymentProblem()
        )
        assertEquals(
            SparkPaymentProblem.Expired,
            SdkException.SparkException(
                "v1=Service error: service provider error: graphql error: Invoice for lightning send " +
                    "request 01a1a1b8-4da9-e342-0000-f66d2c4ce9f3 has expired"
            ).paymentProblem()
        )
    }

    @Test
    fun aLostConnectionToBreezsServiceIsANetworkProblem() {
        // Captured from Breez SDK 0.27.2 on Lightspark's hosted regtest. Unrecognised, a quote
        // showed "Payment failed" with this text instead of the network message (MOB-112).
        assertEquals(
            SparkPaymentProblem.Network,
            SdkException.SparkException(
                "v1=Service error: service provider error: network error: Request error: error " +
                    "sending request for url (https://api.lightspark.com/graphql/spark/rc) : client " +
                    "error (SendRequest) : connection error : peer closed connection without " +
                    "sending TLS close_notify"
            ).paymentProblem()
        )
    }
}
