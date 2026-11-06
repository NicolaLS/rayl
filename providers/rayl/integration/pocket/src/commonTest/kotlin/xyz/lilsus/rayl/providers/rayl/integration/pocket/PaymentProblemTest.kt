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

    @Test
    fun recognizesTheRefusalsTheRealSdkSendsWhenSending() {
        // Captured from Breez SDK 0.27.2 on regtest (BreezSdkContractTest). Unrecognised, they
        // were shown as "payment outcome unknown" (MOB-101).
        assertEquals(
            PocketPaymentProblem.InsufficientFunds,
            SdkException.SparkException("v1=Tree service error: insufficient funds").paymentProblem()
        )
        assertEquals(
            PocketPaymentProblem.Expired,
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
            PocketPaymentProblem.Network,
            SdkException.SparkException(
                "v1=Service error: service provider error: network error: Request error: error " +
                    "sending request for url (https://api.lightspark.com/graphql/spark/rc) : client " +
                    "error (SendRequest) : connection error : peer closed connection without " +
                    "sending TLS close_notify"
            ).paymentProblem()
        )
    }
}
