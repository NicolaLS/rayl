package xyz.lilsus.raylsuite.feature.paymentflow

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider
import xyz.lilsus.raylsuite.core.payment.LnurlPayClient
import xyz.lilsus.raylsuite.core.ui.platform.HapticFeedbackManager
import xyz.lilsus.raylsuite.feature.currencysettings.CurrencyPreferences
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentCurrencyManager
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController
import xyz.lilsus.raylsuite.feature.paymentsettings.PaymentConfirmationPolicy
import xyz.lilsus.raylsuite.feature.paymentsettings.PaymentPreferencesRepository
import xyz.lilsus.raylsuite.feature.paymentui.PaymentIntent

/**
 * Lightning payment flow for wallets that pay BOLT11 invoices resolved on the device: admission,
 * LNURL resolution, confirmation, session history and Hub reporting. [settlement] owns funding
 * and submission.
 */
class PaymentFlowCoordinator<P : SettleablePayment>(
    private val settlement: PaymentSettlement<P>,
    lnurlPayClient: LnurlPayClient,
    bitcoinPriceProvider: BitcoinPriceProvider,
    currencyPreferences: CurrencyPreferences,
    paymentPreferences: PaymentPreferencesRepository,
    private val paymentHub: PaymentHubController,
    private val haptics: HapticFeedbackManager,
    coroutineContext: CoroutineContext = Dispatchers.Main
) {
    private val scope = CoroutineScope(SupervisorJob() + coroutineContext)
    private val currencyManager = PaymentCurrencyManager(bitcoinPriceProvider, scope)
    private val presentation = PaymentPresentationPhase(currencyManager)
    private val history = PaymentHistory(currencyManager)
    private val operation = PaymentOperation(scope)
    private var runtimePreferences = PaymentRuntimePreferences()
    private val confirmation =
        PaymentConfirmationPhase(
            settlement = settlement,
            currencyManager = currencyManager,
            confirmationPolicy = PaymentConfirmationPolicy(paymentPreferences),
            presentation = presentation
        )
    private val admission =
        PaymentAdmissionPhase(
            lnurlPayClient = lnurlPayClient,
            currencyManager = currencyManager,
            history = history,
            presentation = presentation,
            notifyScanSuccess = ::notifyScanSuccess
        )
    val uiState: StateFlow<PaymentUiState> = presentation.uiState
    val events: SharedFlow<PaymentEvent> = presentation.events
    val sessionTransactions: StateFlow<List<SessionTransactionItem>> =
        history.displayItems
    val transactionDetailNavigationTarget: StateFlow<String?> =
        presentation.transactionDetailNavigationTarget
    val newSessionTransactionCount: StateFlow<Int> =
        presentation.newSessionTransactionCount

    init {
        scope.launch {
            paymentPreferences.preferences.collectLatest { preferences ->
                runtimePreferences =
                    PaymentRuntimePreferences(
                        vibrateOnScan = preferences.vibrateOnScan,
                        vibrateOnPayment = preferences.vibrateOnPayment,
                        offerToSaveNewTargets = preferences.offerToSaveNewTargets
                    )
            }
        }
        scope.launch {
            paymentHub.paymentRequests.collect { intent ->
                launchOperation { handleAdmissionResult(admission.payTarget(intent)) }
            }
        }
        scope.launch {
            currencyPreferences.code.collectLatest { code ->
                currencyManager.setPreferredCurrency(CurrencyCatalog.infoFor(code).currency)
            }
        }
        scope.launch {
            currencyManager.state.collectLatest {
                admission.refreshManualAmountState()
                presentation.refreshResult()
                history.refreshDisplayItems()
            }
        }
        scope.launch {
            currencyManager.errors.collect { error ->
                // A background quote failure must not interrupt SAT entry or payment submission.
                presentation.emitErrorEvent(error.toPaymentUiError())
            }
        }
        scope.launch {
            history.displayItems.collect { items ->
                presentation.updateSessionTransactionIds(
                    items.mapTo(mutableSetOf(), SessionTransactionItem::id)
                )
            }
        }
    }

    val isSubmitting: StateFlow<Boolean> get() = operation.active

    fun clear() {
        operation.reset()
        scope.cancel()
    }

    fun resetSession() {
        operation.reset()
        admission.reset(currencyManager.state.value)
        confirmation.reset()
        presentation.reset()
        history.reset()
        paymentHub.resetSession()
    }

    fun dispatch(intent: PaymentIntent) {
        if (operation.active.value) return
        when (intent) {
            PaymentIntent.DismissResult -> presentation.dismissResult()

            is PaymentIntent.TransactionDetailNavigationHandled ->
                presentation.onTransactionDetailNavigationHandled(intent.id)

            PaymentIntent.SessionTransactionsOpened ->
                presentation.onSessionTransactionsOpened(
                    sessionTransactions.value.map(SessionTransactionItem::id)
                )

            is PaymentIntent.QrCodeScanned ->
                launchOperation {
                    handleAdmissionResult(
                        admission.handlePaymentInput(rawInput = intent.rawValue)
                    )
                }

            PaymentIntent.ManualAmountDismiss -> admission.dismissManualAmount()

            PaymentIntent.ManualAmountSubmit ->
                launchOperation { handleAdmissionResult(admission.submitManualAmount()) }

            is PaymentIntent.ManualAmountKeyPress ->
                admission.updateManualAmount(intent.key)

            PaymentIntent.ConfirmPaymentDismiss ->
                if (confirmation.dismiss()) presentation.showActive()

            PaymentIntent.ConfirmPaymentSubmit ->
                launchOperation { handleConfirmationResult(confirmation.submit()) }

            is PaymentIntent.RawInputSubmitted ->
                launchOperation {
                    handleAdmissionResult(
                        admission.handlePaymentInput(rawInput = intent.rawValue)
                    )
                }
        }
    }

    private suspend fun handleAdmissionResult(result: AdmissionResult) {
        currentCoroutineContext().ensureActive()
        when (result) {
            AdmissionResult.Presented -> Unit

            is AdmissionResult.Payment ->
                handleConfirmationResult(
                    confirmation.requestPayment(result.payment)
                )
        }
    }

    private suspend fun handleConfirmationResult(result: ConfirmationResult<P>) {
        currentCoroutineContext().ensureActive()
        when (result) {
            ConfirmationResult.Presented -> Unit

            is ConfirmationResult.Execute ->
                executePayment(result.payment)
        }
    }

    private suspend fun executePayment(payment: P) {
        presentation.showLoading()
        val amountMsats = payment.amountOverrideMsats ?: payment.invoice.amount?.msat ?: 0L
        val paymentHash = payment.invoice.paymentHash.toHex()
        val outcome = settlement.settle(payment)
        currentCoroutineContext().ensureActive()
        admission.reset(currencyManager.state.value)
        when (outcome) {
            is SettlementOutcome.Paid -> {
                val result = CompletedPayment(
                    amountMsats = if (outcome.wasAlreadyPaid) 0L else amountMsats,
                    feeMsats = outcome.feeMsats,
                    wasAlreadyPaid = outcome.wasAlreadyPaid,
                    preimage = outcome.preimage
                )
                history.record(
                    paymentHash = paymentHash,
                    amountMsats = amountMsats,
                    result = result,
                    recipient = payment.targetContext?.address?.full,
                    domain = payment.lnurlPayDisplay?.domain
                )
                if (!outcome.wasAlreadyPaid) {
                    reportPaymentSuccess(payment.targetContext, amountMsats)
                }
                if (runtimePreferences.vibrateOnPayment) haptics.notifyPaymentSuccess()
                presentation.showPaymentSuccess(result)
            }

            is SettlementOutcome.Failed -> {
                history.record(paymentHash, amountMsats, error = outcome.error)
                presentation.presentError(outcome.error)
            }

            is SettlementOutcome.Unknown -> presentation.presentError(outcome.error)
        }
    }

    private fun reportPaymentSuccess(context: HubTargetContext?, paidMsats: Long) {
        if (context == null || paidMsats <= 0L) return
        val targetId = context.targetId
        if (targetId != null) {
            paymentHub.recordSuccessfulPayment(targetId)
        } else if (runtimePreferences.offerToSaveNewTargets) {
            paymentHub.offerSave(context.address)
        }
    }

    private fun launchOperation(block: suspend () -> Unit) {
        operation.launch {
            try {
                block()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                currentCoroutineContext().ensureActive()
                presentation.presentError(settlement.errorFor(cause))
            }
        }
    }

    private fun notifyScanSuccess() {
        if (runtimePreferences.vibrateOnScan) haptics.notifyScanSuccess()
    }
}

private data class PaymentRuntimePreferences(
    val vibrateOnScan: Boolean = true,
    val vibrateOnPayment: Boolean = true,
    val offerToSaveNewTargets: Boolean = true
)
