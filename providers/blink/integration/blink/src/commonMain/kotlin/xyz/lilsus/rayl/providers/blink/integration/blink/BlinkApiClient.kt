package xyz.lilsus.rayl.providers.blink.integration.blink

import com.apollographql.apollo.ApolloCall
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Error as ApolloGraphQlError
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.network.http.DefaultHttpEngine
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.utils.msat
import kotlin.math.absoluteValue
import xyz.lilsus.rayl.providers.blink.integration.blink.graphql.AuthorizationQuery
import xyz.lilsus.rayl.providers.blink.integration.blink.graphql.BlinkContactsQuery
import xyz.lilsus.rayl.providers.blink.integration.blink.graphql.BlinkPaymentTransactionQuery
import xyz.lilsus.rayl.providers.blink.integration.blink.graphql.FundingWalletsQuery
import xyz.lilsus.rayl.providers.blink.integration.blink.graphql.LnInvoicePaymentSendMutation
import xyz.lilsus.rayl.providers.blink.integration.blink.graphql.LnNoAmountInvoicePaymentSendMutation
import xyz.lilsus.rayl.providers.blink.integration.blink.graphql.LnNoAmountUsdInvoicePaymentSendMutation
import xyz.lilsus.rayl.providers.blink.integration.blink.graphql.fragment.BlinkTransactionPaymentResult
import xyz.lilsus.rayl.providers.blink.integration.blink.graphql.type.LnInvoicePaymentInput
import xyz.lilsus.rayl.providers.blink.integration.blink.graphql.type.LnNoAmountInvoicePaymentInput
import xyz.lilsus.rayl.providers.blink.integration.blink.graphql.type.LnNoAmountUsdInvoicePaymentInput
import xyz.lilsus.rayl.providers.blink.integration.blink.graphql.type.PaymentSendResult
import xyz.lilsus.rayl.providers.blink.integration.blink.graphql.type.TxDirection
import xyz.lilsus.rayl.providers.blink.integration.blink.graphql.type.TxStatus
import xyz.lilsus.rayl.providers.blink.integration.blink.graphql.type.WalletCurrency

/**
 * Client for Blink's GraphQL API.
 * Handles Lightning invoice payments using API key authentication.
 *
 * API Reference: https://dev.blink.sv/public-api-reference.html
 */
class BlinkApiClient(
    private val apolloClient: ApolloClient = createBlinkApolloClient(),
    private val logResponses: Boolean = false
) {
    fun close() = apolloClient.close()

    /**
     * Translates Blink API errors into user-friendly messages.
     * Returns a translated message or null if no specific translation applies.
     */
    private fun translateBlinkError(code: String?, message: String?): BlinkErrorTranslation? {
        val combinedText = listOfNotNull(code, message)
            .joinToString(" ")
            .lowercase()

        return when {
            // Permission/Authorization errors - API key missing write permissions
            combinedText.contains("authorizationerror") ||
                combinedText.contains("not authorized to execute mutations") ||
                (combinedText.contains("not authorized") && combinedText.contains("mutation")) ->
                BlinkErrorTranslation.PermissionDenied

            // Insufficient balance
            (combinedText.contains("insufficient") && combinedText.contains("balance")) ||
                combinedText.contains("insufficientbalance") ||
                (combinedText.contains("not enough") && combinedText.contains("balance")) ->
                BlinkErrorTranslation.InsufficientBalance

            // Route not found (Lightning Network routing failure)
            (combinedText.contains("route") && combinedText.contains("not found")) ||
                combinedText.contains("no_route") ||
                combinedText.contains("routenotfound") ||
                (combinedText.contains("unable to find") && combinedText.contains("path")) ->
                BlinkErrorTranslation.RouteNotFound

            // Invoice expired
            (combinedText.contains("invoice") && combinedText.contains("expired")) ||
                (combinedText.contains("payment request") && combinedText.contains("expired")) ||
                (combinedText.contains("expir") && combinedText.contains("invoice")) ->
                BlinkErrorTranslation.InvoiceExpired

            // Self-payment (trying to pay your own invoice)
            (combinedText.contains("self") && combinedText.contains("payment")) ||
                combinedText.contains("selfpayment") ||
                combinedText.contains("same wallet") ||
                combinedText.contains("pay yourself") ->
                BlinkErrorTranslation.SelfPayment

            // Invalid invoice format
            (combinedText.contains("invalid") && combinedText.contains("invoice")) ||
                (combinedText.contains("invalid") && combinedText.contains("payment request")) ||
                (combinedText.contains("decode") && combinedText.contains("fail")) ||
                combinedText.contains("malformed") ->
                BlinkErrorTranslation.InvalidInvoice

            // Amount too small
            (combinedText.contains("amount") && combinedText.contains("too small")) ||
                (combinedText.contains("below") && combinedText.contains("minimum")) ||
                combinedText.contains("dust") ->
                BlinkErrorTranslation.AmountTooSmall

            // Amount too large / limit exceeded
            (combinedText.contains("amount") && combinedText.contains("too large")) ||
                (combinedText.contains("exceeds") && combinedText.contains("limit")) ||
                (combinedText.contains("limit") && combinedText.contains("exceeded")) ||
                combinedText.contains("withdrawal limit") ->
                BlinkErrorTranslation.LimitExceeded

            // Rate limiting
            (combinedText.contains("rate") && combinedText.contains("limit")) ||
                combinedText.contains("too many requests") ||
                combinedText.contains("throttl") ->
                BlinkErrorTranslation.RateLimited

            else -> null
        }
    }

    /**
     * Returns a user-friendly error for known Blink error patterns.
     */
    private fun createUserFriendlyError(
        code: String?,
        message: String?,
        isAuthError: Boolean = false
    ): BlinkApiError {
        val translation = translateBlinkError(code, message)

        return when {
            translation != null -> when (translation) {
                BlinkErrorTranslation.PermissionDenied ->
                    BlinkApiError.BlinkError(BlinkErrorType.PermissionDenied)

                BlinkErrorTranslation.InsufficientBalance ->
                    BlinkApiError.BlinkError(BlinkErrorType.InsufficientBalance)

                BlinkErrorTranslation.RouteNotFound ->
                    BlinkApiError.BlinkError(BlinkErrorType.RouteNotFound)

                BlinkErrorTranslation.InvoiceExpired ->
                    BlinkApiError.BlinkError(BlinkErrorType.InvoiceExpired)

                BlinkErrorTranslation.SelfPayment ->
                    BlinkApiError.BlinkError(BlinkErrorType.SelfPayment)

                BlinkErrorTranslation.InvalidInvoice ->
                    BlinkApiError.BlinkError(BlinkErrorType.InvalidInvoice)

                BlinkErrorTranslation.AmountTooSmall ->
                    BlinkApiError.BlinkError(BlinkErrorType.AmountTooSmall)

                BlinkErrorTranslation.LimitExceeded ->
                    BlinkApiError.BlinkError(BlinkErrorType.LimitExceeded)

                BlinkErrorTranslation.RateLimited ->
                    BlinkApiError.BlinkError(BlinkErrorType.RateLimited)
            }

            isAuthError ->
                BlinkApiError.BlinkError(BlinkErrorType.InvalidApiKey)

            else ->
                BlinkApiError.PaymentRejected(code = code, message = message)
        }
    }

    private enum class BlinkErrorTranslation {
        PermissionDenied,
        InsufficientBalance,
        RouteNotFound,
        InvoiceExpired,
        SelfPayment,
        InvalidInvoice,
        AmountTooSmall,
        LimitExceeded,
        RateLimited
    }

    /**
     * Fetches the authorization scopes for the provided API key.
     *
     * @param apiKey The Blink API key to check.
     * @return List of scope strings (e.g., ["READ", "WRITE", "RECEIVE"]).
     * @throws [BlinkApiException] on failure.
     */
    suspend fun fetchAuthorizationScopes(apiKey: String): List<String> {
        val data = executeGraphQlRequest(
            apiKey = apiKey,
            logLabel = "Authorization",
            call = apolloClient.query(AuthorizationQuery())
        )

        return data.authorization.scopes
            .mapNotNull { scope -> scope.rawValue.trim().takeIf { it.isNotEmpty() } }
    }

    /** Fetches the custodial wallets and the server-side default used to seed a new connection. */
    internal suspend fun fetchFundingWallets(apiKey: String): BlinkFundingWalletCatalog {
        val data = executeGraphQlRequest(
            apiKey = apiKey,
            logLabel = "FundingWallets",
            call = apolloClient.query(FundingWalletsQuery())
        )

        val account = data.me?.defaultAccount
            ?: throw BlinkApiException(
                BlinkApiError.Unexpected("Missing default account in response")
            )
        val defaultWalletId = account.defaultWallet.id.trim()

        if (defaultWalletId.isBlank()) {
            throw BlinkApiException(
                BlinkApiError.Unexpected("Missing default wallet id in response")
            )
        }

        return BlinkFundingWalletCatalog(
            defaultWalletId = defaultWalletId,
            wallets =
                account.wallets.mapNotNull { wallet ->
                    val id = wallet.id.trim()
                    val currency =
                        wallet.walletCurrency.toFundingWalletCurrency() ?: return@mapNotNull null
                    id.takeIf(String::isNotEmpty)?.let { BlinkFundingWallet(it, currency) }
                }
        )
    }

    /**
     * Fetches contacts saved in Blink for the API key's user.
     */
    @Suppress("DEPRECATION")
    suspend fun fetchContacts(apiKey: String): List<BlinkContact> {
        val data = executeGraphQlRequest(
            apiKey = apiKey,
            logLabel = "BlinkContacts",
            call = apolloClient.query(BlinkContactsQuery())
        )

        return data.me?.contacts.orEmpty()
            .mapNotNull { contact ->
                val handle = contact.handle.trim()
                if (handle.isBlank()) return@mapNotNull null
                BlinkContact(
                    handle = handle,
                    alias = contact.alias?.trim()?.takeIf { it.isNotEmpty() },
                    transactionsCount = contact.transactionsCount,
                    // Blink mobile displays bare contact handles with @blink.sv even when
                    // deprecated globals return legacy domains like pay.bbw.sv.
                    lightningAddressDomain = DEFAULT_LIGHTNING_ADDRESS_DOMAIN
                )
            }
    }

    /**
     * Pays a BOLT11 invoice with an embedded amount.
     *
     * @param apiKey The Blink API key for authentication.
     * @param walletId The Blink wallet id to pay from.
     * @param invoice The BOLT11 invoice to pay.
     * @return [BlinkPaymentResult] on success.
     * @throws [BlinkApiException] on failure.
     */
    suspend fun payInvoice(apiKey: String, walletId: String, invoice: String): BlinkPaymentResult {
        val data = executeGraphQlRequest(
            apiKey = apiKey,
            logLabel = "lnInvoicePaymentSend",
            call = apolloClient.mutation(
                LnInvoicePaymentSendMutation(
                    LnInvoicePaymentInput(
                        paymentRequest = invoice,
                        walletId = walletId
                    )
                )
            )
        )

        return parsePaymentResponse(
            PaymentPayload(
                status = data.lnInvoicePaymentSend.status,
                errors = data.lnInvoicePaymentSend.errors.map {
                    PaymentPayloadError(it.code, it.message)
                },
                transaction = data.lnInvoicePaymentSend.transaction
                    ?.blinkTransactionPaymentResult
            )
        )
    }

    /**
     * Pays a zero-amount BOLT11 invoice with a specified amount.
     *
     * @param apiKey The Blink API key for authentication.
     * @param walletId The Blink wallet id to pay from.
     * @param invoice The zero-amount BOLT11 invoice to pay.
     * @param amountSats The amount to pay in satoshis.
     * @return [BlinkPaymentResult] on success.
     * @throws [BlinkApiException] on failure.
     */
    suspend fun payNoAmountInvoice(
        apiKey: String,
        walletId: String,
        invoice: String,
        amountSats: Long
    ): BlinkPaymentResult {
        val data = executeGraphQlRequest(
            apiKey = apiKey,
            logLabel = "lnNoAmountInvoicePaymentSend",
            call = apolloClient.mutation(
                LnNoAmountInvoicePaymentSendMutation(
                    LnNoAmountInvoicePaymentInput(
                        amount = amountSats,
                        paymentRequest = invoice,
                        walletId = walletId
                    )
                )
            )
        )

        return parsePaymentResponse(
            PaymentPayload(
                status = data.lnNoAmountInvoicePaymentSend.status,
                errors = data.lnNoAmountInvoicePaymentSend.errors.map {
                    PaymentPayloadError(it.code, it.message)
                },
                transaction = data.lnNoAmountInvoicePaymentSend.transaction
                    ?.blinkTransactionPaymentResult
            )
        )
    }

    /** Pays a zero-amount BOLT11 invoice from a Stablesats wallet. */
    suspend fun payNoAmountUsdInvoice(
        apiKey: String,
        walletId: String,
        invoice: String,
        amountCents: Long
    ): BlinkPaymentResult {
        val data = executeGraphQlRequest(
            apiKey = apiKey,
            logLabel = "lnNoAmountUsdInvoicePaymentSend",
            call = apolloClient.mutation(
                LnNoAmountUsdInvoicePaymentSendMutation(
                    LnNoAmountUsdInvoicePaymentInput(
                        amount = amountCents,
                        paymentRequest = invoice,
                        walletId = walletId
                    )
                )
            )
        )

        return parsePaymentResponse(
            PaymentPayload(
                status = data.lnNoAmountUsdInvoicePaymentSend.status,
                errors = data.lnNoAmountUsdInvoicePaymentSend.errors.map {
                    PaymentPayloadError(it.code, it.message)
                },
                transaction = data.lnNoAmountUsdInvoicePaymentSend.transaction
                    ?.blinkTransactionPaymentResult
            )
        )
    }

    /** Returns null while the exact submitted transaction is still pending. */
    internal suspend fun fetchPaymentOutcome(
        apiKey: String,
        walletId: String,
        transactionId: String
    ): BlinkPaymentOutcome? {
        val data = executeGraphQlRequest(
            apiKey = apiKey,
            logLabel = "BlinkPaymentTransaction",
            call = apolloClient.query(BlinkPaymentTransactionQuery(walletId, transactionId))
        )
        val transaction = data.me?.defaultAccount?.walletById?.transactionById
            ?.blinkTransactionPaymentResult
            ?: throw BlinkApiException(BlinkApiError.Unexpected("Missing payment transaction"))
        if (transaction.id != transactionId || transaction.direction != TxDirection.SEND) {
            throw BlinkApiException(BlinkApiError.Unexpected("Unexpected payment transaction"))
        }

        return when (transaction.status) {
            TxStatus.SUCCESS -> BlinkPaymentOutcome.Paid(
                preimageHex = transaction.preimage()?.toHex(),
                feesPaidMsats = transaction.feesPaid()?.msat
            )

            TxStatus.FAILURE -> BlinkPaymentOutcome.DefinitiveFailure(
                BlinkApiError.PaymentRejected()
            )

            TxStatus.PENDING -> null

            TxStatus.UNKNOWN__ -> throw BlinkApiException(
                BlinkApiError.Unexpected("Unknown payment transaction status")
            )
        }
    }

    private suspend fun <D : Operation.Data> executeGraphQlRequest(
        apiKey: String,
        logLabel: String,
        call: ApolloCall<D>
    ): D {
        val response = try {
            call
                .addHttpHeader(API_KEY_HEADER, apiKey)
                .execute()
        } catch (e: ApolloException) {
            throw mapApolloException(e)
        }

        logGraphQlResponse(logLabel, response)

        response.exception?.let { exception ->
            throw mapApolloException(exception)
        }

        response.errors?.firstOrNull()?.let { error ->
            throw BlinkApiException(mapGraphQlError(error))
        }

        return response.data
            ?: throw BlinkApiException(BlinkApiError.Unexpected("Missing data in response"))
    }

    private fun mapApolloException(exception: ApolloException): BlinkApiException {
        if (exception is ApolloHttpException) {
            val error = when (exception.statusCode) {
                HTTP_UNAUTHORIZED -> BlinkApiError.BlinkError(BlinkErrorType.InvalidApiKey)
                HTTP_TOO_MANY_REQUESTS -> BlinkApiError.BlinkError(BlinkErrorType.RateLimited)
                else -> BlinkApiError.NetworkUnavailable
            }
            return BlinkApiException(error, exception)
        }

        return BlinkApiException(
            if (exception.isTimeout()) BlinkApiError.Timeout else BlinkApiError.NetworkUnavailable,
            exception
        )
    }

    private fun mapGraphQlError(error: ApolloGraphQlError): BlinkApiError {
        val code = error.extensionCode()
        val isAuthError = code == "UNAUTHENTICATED" || code == "FORBIDDEN"
        return createUserFriendlyError(code, error.message, isAuthError)
    }

    private fun parsePaymentResponse(payload: PaymentPayload): BlinkPaymentResult {
        payload.errors.firstOrNull()?.let { error ->
            throw BlinkApiException(createUserFriendlyError(error.code, error.message))
        }

        return when (payload.status) {
            PaymentSendResult.SUCCESS -> BlinkPaymentResult.Success(
                feesPaid = payload.transaction?.feesPaid(),
                preimage = payload.transaction?.preimage()
            )

            PaymentSendResult.PENDING -> BlinkPaymentResult.Pending(
                transactionId = payload.transaction?.id
            )

            PaymentSendResult.ALREADY_PAID -> BlinkPaymentResult.AlreadyPaid

            PaymentSendResult.FAILURE -> throw BlinkApiException(BlinkApiError.PaymentRejected())

            PaymentSendResult.UNKNOWN__ -> throw BlinkApiException(
                BlinkApiError.Unexpected("Unknown payment status: ${payload.status.rawValue}")
            )

            null -> throw BlinkApiException(BlinkApiError.Unexpected("Missing status in response"))
        }
    }

    private fun ApolloGraphQlError.extensionCode(): String? =
        extensions?.get("code")?.let { value ->
            when (value) {
                is String -> value
                else -> value.toString()
            }
        }

    private fun Throwable.isTimeout(): Boolean {
        val text = message?.lowercase().orEmpty()
        return text.contains("timeout") ||
            text.contains("timed out") ||
            cause?.isTimeout() == true
    }

    private fun BlinkTransactionPaymentResult.feesPaid(): MilliSatoshi? =
        settlementFee.toLightningFee(settlementCurrency)

    private fun BlinkTransactionPaymentResult.preimage(): ByteVector32? =
        settlementVia.onSettlementViaIntraLedger?.preImage?.toPreimageOrNull()
            ?: settlementVia.onSettlementViaLn?.preImage?.toPreimageOrNull()

    private fun String.toPreimageOrNull(): ByteVector32? =
        runCatching { ByteVector32.fromValidHex(trim()) }.getOrNull()

    private fun Long.toLightningFee(settlementCurrency: WalletCurrency): MilliSatoshi? {
        if (settlementCurrency != WalletCurrency.BTC) return null

        return absoluteValue
            .takeIf { it <= Long.MAX_VALUE / 1000L }
            ?.times(1000L)
            ?.msat
    }

    private fun <D : Operation.Data> logGraphQlResponse(
        logLabel: String,
        response: ApolloResponse<D>
    ) {
        if (!logResponses) return
        println(
            "Blink GraphQL [$logLabel] " +
                "data=${response.data != null} " +
                "errors=${response.errors?.size ?: 0} " +
                "exception=${response.exception?.message.orEmpty()}"
        )
    }

    private data class PaymentPayload(
        val status: PaymentSendResult?,
        val errors: List<PaymentPayloadError>,
        val transaction: BlinkTransactionPaymentResult?
    )

    private data class PaymentPayloadError(val code: String?, val message: String?)

    companion object {
        private const val BLINK_API_URL = "https://api.blink.sv/graphql"
        private const val API_KEY_HEADER = "X-API-KEY"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val DEFAULT_LIGHTNING_ADDRESS_DOMAIN = "blink.sv"

        /** [serverUrl] is Blink's production API; tests point it at a local Blink stack. */
        fun createBlinkApolloClient(serverUrl: String = BLINK_API_URL): ApolloClient =
            ApolloClient.Builder()
                .serverUrl(serverUrl)
                .httpEngine(DefaultHttpEngine(timeoutMillis = BLINK_HTTP_TIMEOUT_MS))
                .build()

        private const val BLINK_HTTP_TIMEOUT_MS = 90_000L
    }
}

internal data class BlinkFundingWalletCatalog(
    val defaultWalletId: String,
    val wallets: List<BlinkFundingWallet>
)

private fun WalletCurrency.toFundingWalletCurrency(): BlinkWalletCurrency? = when (this) {
    WalletCurrency.BTC -> BlinkWalletCurrency.BTC
    WalletCurrency.USD -> BlinkWalletCurrency.USD
    WalletCurrency.UNKNOWN__ -> null
}

/**
 * Result of a Blink payment operation.
 */
sealed interface BlinkPaymentResult {
    /** Payment completed successfully. */
    data class Success(val feesPaid: MilliSatoshi? = null, val preimage: ByteVector32? = null) :
        BlinkPaymentResult

    /** The caller must wait for this transaction to settle before completing the payment flow. */
    data class Pending(val transactionId: String?) : BlinkPaymentResult

    /** Invoice was already paid. */
    data object AlreadyPaid : BlinkPaymentResult
}
