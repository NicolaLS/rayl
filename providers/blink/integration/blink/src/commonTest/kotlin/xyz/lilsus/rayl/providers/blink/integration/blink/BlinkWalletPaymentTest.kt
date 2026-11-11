package xyz.lilsus.rayl.providers.blink.integration.blink

import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import xyz.lilsus.rayl.providers.blink.integration.blink.graphql.AuthorizationQuery
import xyz.lilsus.rayl.providers.blink.integration.blink.graphql.BlinkPaymentTransactionQuery
import xyz.lilsus.rayl.providers.blink.integration.blink.graphql.FundingWalletsQuery
import xyz.lilsus.rayl.providers.blink.integration.blink.graphql.LnInvoicePaymentSendMutation
import xyz.lilsus.rayl.providers.blink.integration.blink.graphql.LnNoAmountUsdInvoicePaymentSendMutation
import xyz.lilsus.raylsuite.core.model.WalletStartMode

class BlinkWalletPaymentTest {
    @Test
    fun freshSetupAndPaymentWithoutAConnectionNeverReadOldCredentials() = runTest {
        val secure = TestSecureStringStore().apply { forbidReads = true }
        val wallet = BlinkWallet(
            apiClient = createClient { error("No network request expected") },
            credentialStore = BlinkCredentialStore(secure),
            isNetworkAvailable = { true },
            startMode = WalletStartMode.Fresh
        )
        assertNull(wallet.connection.value)
        val result = wallet.submitPayment(BlinkPaymentRequest("lnbc1test", TEST_BTC_WALLET))
        assertIs<BlinkPaymentOutcome.DefinitiveFailure>(result)
        assertEquals(0, secure.reads)
        wallet.close()
    }

    @Test
    fun connectSeedsTheServerDefaultByWalletIdentity() = runBlocking {
        val store = BlinkCredentialStore(TestSecureStringStore())
        val wallet =
            BlinkWallet(
                apiClient =
                    createClient { request ->
                        when (request.operation) {
                            is AuthorizationQuery ->
                                request.responseFromJson(authorizationResponse())

                            is FundingWalletsQuery ->
                                request.responseFromJson(
                                    fundingWalletsResponse(
                                        defaultWalletId = TEST_USD_WALLET.id,
                                        wallets = listOf(TEST_USD_WALLET, TEST_BTC_WALLET)
                                    )
                                )

                            else -> error("Unexpected operation ${request.operation.name()}")
                        }
                    },
                credentialStore = store,
                isNetworkAvailable = { true }
            )

        wallet.connect("test-api-key")

        assertEquals(TEST_USD_WALLET, wallet.selectedFundingWallet.value)
        assertEquals(TEST_USD_WALLET, store.read()?.selectedFundingWallet)
    }

    @Test
    fun pendingApiResponseWithoutTransactionRemainsUnconfirmed() = runBlocking {
        val wallet =
            createWallet(
                responseJson = paymentResponse(status = "PENDING")
            )

        val result = wallet.submitPayment(BlinkPaymentRequest("lnbc1test", TEST_BTC_WALLET))

        assertIs<BlinkPaymentOutcome.StatusUnknown>(result)
        Unit
    }

    @Test
    fun pendingSubmissionWaitsForItsOwnTransactionWithoutResubmitting() = runTest {
        var submissionCount = 0
        var statusQueryCount = 0
        val wallet = BlinkWallet(
            apiClient = createClient { request ->
                when (val operation = request.operation) {
                    is LnInvoicePaymentSendMutation -> {
                        submissionCount += 1
                        request.responseFromJson(
                            paymentResponse("PENDING", transaction = transactionResponse("PENDING"))
                        )
                    }

                    is BlinkPaymentTransactionQuery -> {
                        statusQueryCount += 1
                        assertEquals(TEST_BTC_WALLET.id, operation.walletId)
                        assertEquals("transaction-123", operation.transactionId)
                        request.responseFromJson(
                            transactionQueryResponse(
                                if (statusQueryCount == 1) "PENDING" else "SUCCESS"
                            )
                        )
                    }

                    else -> error("Unexpected operation ${operation.name()}")
                }
            },
            credentialStore = credentialStore(TEST_BTC_WALLET),
            isNetworkAvailable = { true }
        )

        val result = wallet.submitPayment(BlinkPaymentRequest("lnbc1test", TEST_BTC_WALLET))

        assertEquals(BlinkPaymentOutcome.Paid(TEST_PAYMENT_PREIMAGE, 10_000L), result)
        assertEquals(1, submissionCount)
        assertEquals(2, statusQueryCount)
    }

    @Test
    fun aPaymentThatStaysPendingIsReportedUnknownInsteadOfWaitingForever() = runTest {
        val wallet = walletWithPendingSubmission(transactionQueryResponse("PENDING"))

        val result = wallet.submitPayment(BlinkPaymentRequest("lnbc1test", TEST_BTC_WALLET))

        // Unknown, never failed: the payment was submitted and may still settle.
        assertEquals(BlinkPaymentOutcome.StatusUnknown(BlinkApiError.Timeout), result)
        assertEquals(30_000L, testScheduler.currentTime)
    }

    @Test
    fun pendingSubmissionReturnsTheConfirmedFailure() = runTest {
        val wallet = walletWithPendingSubmission(transactionQueryResponse("FAILURE"))

        val result = wallet.submitPayment(BlinkPaymentRequest("lnbc1test", TEST_BTC_WALLET))

        assertEquals(
            BlinkPaymentOutcome.DefinitiveFailure(BlinkApiError.PaymentRejected()),
            result
        )
    }

    @Test
    fun statusQueryFailureDoesNotDeclareTheSubmittedPaymentFailed() = runTest {
        val wallet = walletWithPendingSubmission(
            """{"errors":[{"message":"Unauthorized","extensions":{"code":"UNAUTHENTICATED"}}]}"""
        )

        val result = wallet.submitPayment(BlinkPaymentRequest("lnbc1test", TEST_BTC_WALLET))

        assertEquals(
            BlinkPaymentOutcome.StatusUnknown(BlinkApiError.BlinkError(BlinkErrorType.InvalidApiKey)),
            result
        )
    }

    @Test
    fun rejectedApiResponseIsDefinitiveFailure() = runBlocking {
        val wallet =
            createWallet(
                responseJson =
                    paymentResponse(
                        status = "FAILURE",
                        errors =
                            """
                    [{
                        "code": "INSUFFICIENT_BALANCE",
                        "message": "Insufficient balance"
                    }]
                            """.trimIndent()
                    )
            )

        val result = wallet.submitPayment(BlinkPaymentRequest("lnbc1test", TEST_BTC_WALLET))

        assertEquals(
            BlinkPaymentOutcome.DefinitiveFailure(
                BlinkApiError.BlinkError(BlinkErrorType.InsufficientBalance)
            ),
            result
        )
    }

    @Test
    fun unknownApiStatusHasUnknownPaymentStatus() = runBlocking {
        val wallet =
            createWallet(
                responseJson = paymentResponse(status = "FUTURE_STATUS")
            )

        val result = wallet.submitPayment(BlinkPaymentRequest("lnbc1test", TEST_BTC_WALLET))

        assertIs<BlinkPaymentOutcome.StatusUnknown>(result)
        Unit
    }

    @Test
    fun disconnectedWalletDoesNotSubmitPayment() = runBlocking {
        var requestCount = 0
        val client =
            createClient {
                requestCount += 1
                it.responseFromJson(paymentResponse(status = "SUCCESS"))
            }
        val wallet =
            BlinkWallet(
                apiClient = client,
                credentialStore = BlinkCredentialStore(TestSecureStringStore()),
                isNetworkAvailable = { true }
            )

        val result = wallet.submitPayment(BlinkPaymentRequest("lnbc1test", TEST_BTC_WALLET))

        assertEquals(
            BlinkPaymentOutcome.DefinitiveFailure(BlinkApiError.MissingWalletConnection),
            result
        )
        assertEquals(0, requestCount)
    }

    @Test
    fun submittedPaymentKeepsSnapshottedWalletAfterSelectionChanges() = runBlocking {
        var submittedWalletId: String? = null
        val store = credentialStore(TEST_BTC_WALLET)
        val wallet =
            BlinkWallet(
                apiClient =
                    createClient { request ->
                        submittedWalletId =
                            assertIs<LnInvoicePaymentSendMutation>(request.operation).input.walletId
                        request.responseFromJson(paymentResponse(status = "SUCCESS"))
                    },
                credentialStore = store,
                isNetworkAvailable = { true }
            )
        val paymentSnapshot = TEST_BTC_WALLET

        wallet.selectFundingWallet(TEST_USD_WALLET)
        wallet.submitPayment(BlinkPaymentRequest("lnbc1test", paymentSnapshot))

        assertEquals(TEST_BTC_WALLET.id, submittedWalletId)
        assertEquals(TEST_USD_WALLET, wallet.selectedFundingWallet.value)
    }

    @Test
    fun refreshDoesNotFollowAnExternallyChangedDefault() = runBlocking {
        val store = credentialStore(TEST_BTC_WALLET)
        val wallet =
            BlinkWallet(
                apiClient =
                    createClient {
                        it.responseFromJson(
                            fundingWalletsResponse(
                                defaultWalletId = TEST_USD_WALLET.id,
                                wallets = listOf(TEST_BTC_WALLET, TEST_USD_WALLET)
                            )
                        )
                    },
                credentialStore = store,
                isNetworkAvailable = { true }
            )

        wallet.refreshFundingWallets()

        assertEquals(TEST_BTC_WALLET, wallet.selectedFundingWallet.value)
        assertEquals(TEST_BTC_WALLET, store.read()?.selectedFundingWallet)
    }

    @Test
    fun missingSelectedWalletFailsClosed() = runBlocking {
        val store = credentialStore(TEST_BTC_WALLET)
        val wallet =
            BlinkWallet(
                apiClient =
                    createClient {
                        it.responseFromJson(
                            fundingWalletsResponse(
                                defaultWalletId = TEST_USD_WALLET.id,
                                wallets = listOf(TEST_USD_WALLET)
                            )
                        )
                    },
                credentialStore = store,
                isNetworkAvailable = { true }
            )

        wallet.refreshFundingWallets()

        assertNull(wallet.selectedFundingWallet.value)
        assertNull(store.read()?.selectedFundingWallet)
        val error = runCatching { wallet.prepareFundingWallet() }.exceptionOrNull()
        assertEquals(
            BlinkApiError.FundingWalletUnavailable,
            assertIs<BlinkApiException>(error).error
        )
    }

    @Test
    fun usdZeroAmountPaymentUsesUsdMutationAndCents() = runBlocking {
        var submittedWalletId: String? = null
        var submittedCents: Long? = null
        val wallet =
            BlinkWallet(
                apiClient =
                    createClient { request ->
                        val operation =
                            assertIs<LnNoAmountUsdInvoicePaymentSendMutation>(request.operation)
                        submittedWalletId = operation.input.walletId
                        submittedCents = operation.input.amount
                        request.responseFromJson(
                            paymentResponse(
                                status = "SUCCESS",
                                operation = "lnNoAmountUsdInvoicePaymentSend"
                            )
                        )
                    },
                credentialStore = credentialStore(TEST_USD_WALLET),
                isNetworkAvailable = { true }
            )

        val result =
            wallet.submitPayment(
                BlinkPaymentRequest(
                    invoice = "lnbc1test",
                    fundingWallet = TEST_USD_WALLET,
                    amount = BlinkPaymentAmount.Usd(cents = 125L)
                )
            )

        assertIs<BlinkPaymentOutcome.Paid>(result)
        assertEquals(TEST_USD_WALLET.id, submittedWalletId)
        assertEquals(125L, submittedCents)
    }

    private fun createWallet(responseJson: String): BlinkWallet = BlinkWallet(
        apiClient = createClient { it.responseFromJson(responseJson) },
        credentialStore = credentialStore(TEST_BTC_WALLET),
        isNetworkAvailable = { true }
    )

    private fun credentialStore(wallet: BlinkFundingWallet): BlinkCredentialStore =
        BlinkCredentialStore(TestSecureStringStore()).also { store ->
            store.save(
                BlinkCredentials(
                    apiKey = "test-api-key",
                    selectedFundingWallet = wallet
                )
            )
        }

    private fun createClient(handler: (ApolloRequest<*>) -> ApolloResponse<*>): BlinkApiClient = BlinkApiClient(
        createBlinkApolloTestClient(BlinkApolloTestTransport(handler))
    )

    private fun walletWithPendingSubmission(statusResponse: String): BlinkWallet = BlinkWallet(
        apiClient = createClient { request ->
            request.responseFromJson(
                when (request.operation) {
                    is LnInvoicePaymentSendMutation ->
                        paymentResponse("PENDING", transaction = transactionResponse("PENDING"))

                    is BlinkPaymentTransactionQuery -> statusResponse

                    else -> error("Unexpected operation ${request.operation.name()}")
                }
            )
        },
        credentialStore = credentialStore(TEST_BTC_WALLET),
        isNetworkAvailable = { true }
    )

    private fun transactionQueryResponse(status: String): String =
        """
        {
            "data": {
                "me": {
                    "defaultAccount": {
                        "walletById": {
                            "__typename": "BTCWallet",
                            "transactionById": ${transactionResponse(status)}
                        }
                    }
                }
            }
        }
        """.trimIndent()

    private fun transactionResponse(status: String): String =
        """
        {
            "__typename": "Transaction",
            "id": "transaction-123",
            "status": "$status",
            "direction": "SEND",
            "settlementFee": -10,
            "settlementCurrency": "BTC",
            "settlementVia": {
                "__typename": "SettlementViaLn",
                "preImage": "$TEST_PAYMENT_PREIMAGE"
            }
        }
        """.trimIndent()

    private fun paymentResponse(
        status: String,
        errors: String = "[]",
        operation: String = "lnInvoicePaymentSend",
        transaction: String = "null"
    ): String =
        """
        {
            "data": {
                "$operation": {
                    "status": "$status",
                    "errors": $errors,
                    "transaction": $transaction
                }
            }
        }
        """.trimIndent()

    private fun authorizationResponse(): String =
        """
        {
            "data": {
                "authorization": {
                    "scopes": ["READ", "WRITE"]
                }
            }
        }
        """.trimIndent()

    private fun fundingWalletsResponse(defaultWalletId: String, wallets: List<BlinkFundingWallet>): String {
        val encodedWallets =
            wallets.joinToString(",") { wallet ->
                """{"id":"${wallet.id}","walletCurrency":"${wallet.currency.name}"}"""
            }
        return """
        {
            "data": {
                "me": {
                    "defaultAccount": {
                        "defaultWallet": {"id": "$defaultWalletId"},
                        "wallets": [$encodedWallets]
                    }
                }
            }
        }
        """.trimIndent()
    }

    private companion object {
        const val TEST_PAYMENT_PREIMAGE =
            "1111111111111111111111111111111111111111111111111111111111111111"
        val TEST_BTC_WALLET = BlinkFundingWallet("wallet-btc", BlinkWalletCurrency.BTC)
        val TEST_USD_WALLET = BlinkFundingWallet("wallet-usd", BlinkWalletCurrency.USD)
    }
}
