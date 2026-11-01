package xyz.lilsus.rayl.providers.blink.integration.blink

import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.utils.msat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class BlinkApiClientTest {
    @Test
    fun payInvoiceParsesTransaction() = runTest {
        val client = createClient(
            responseJson = """{
                "data": {
                    "lnInvoicePaymentSend": {
                        "status": "SUCCESS",
                        "errors": [],
                        "transaction": {
                            "__typename": "Transaction",
                            "id": "transaction-123",
                            "status": "SUCCESS",
                            "direction": "SEND",
                            "settlementFee": -10,
                            "settlementCurrency": "BTC",
                            "settlementVia": {
                                "__typename": "SettlementViaLn",
                                "preImage": "$TEST_PREIMAGE"
                            }
                        }
                    }
                }
            }"""
        )

        val result = client.payInvoice("test-api-key", "wallet-123", "lnbc1test")

        assertEquals(
            BlinkPaymentResult.Success(
                feesPaid = 10_000L.msat,
                preimage = ByteVector32.fromValidHex(TEST_PREIMAGE)
            ),
            result
        )
    }

    private fun createClient(responseJson: String): BlinkApiClient = createClient { request -> request.responseFromJson(responseJson) }

    private fun createClient(handler: (ApolloRequest<*>) -> ApolloResponse<*>): BlinkApiClient {
        val transport = BlinkApolloTestTransport(handler)
        return BlinkApiClient(createBlinkApolloTestClient(transport))
    }
}

private const val TEST_PREIMAGE =
    "1111111111111111111111111111111111111111111111111111111111111111"
