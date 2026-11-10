package xyz.lilsus.raylsuite.feature.paymentflow

import fr.acinq.lightning.utils.currentTimestampSeconds
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
import xyz.lilsus.raylsuite.core.raylapi.TelemetryPaymentError
import xyz.lilsus.raylsuite.core.raylapi.TelemetryPaymentOutcome
import xyz.lilsus.raylsuite.core.raylapi.TelemetryPaymentSource
import xyz.lilsus.raylsuite.core.telemetry.RaylTrace
import xyz.lilsus.raylsuite.core.telemetry.TraceSpan
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
    coroutineContext: CoroutineContext = Dispatchers.Main,
    /** Unix time in seconds, for invoice expiry; replaceable so tests can let an invoice expire. */
    nowSeconds: () -> Long = { currentTimestampSeconds() },
    private val telemetry: PaymentTelemetry = PaymentTelemetry()
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
            presentation = presentation,
            nowSeconds = nowSeconds
        )
    private val admission =
        PaymentAdmissionPhase(
            lnurlPayClient = lnurlPayClient,
            currencyManager = currencyManager,
            history = history,
            presentation = presentation,
            notifyScanSuccess = ::notifyScanSuccess,
            nowSeconds = nowSeconds
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
                startAttempt(TelemetryPaymentSource.Hub)
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
        telemetry.abandoned()
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

            is PaymentIntent.QrCodeScanned -> {
                startAttempt(TelemetryPaymentSource.Scan)
                launchOperation {
                    handleAdmissionResult(
                        admission.handlePaymentInput(rawInput = intent.rawValue)
                    )
                }
            }

            PaymentIntent.ManualAmountDismiss -> {
                telemetry.abandoned()
                admission.dismissManualAmount()
            }

            PaymentIntent.ManualAmountSubmit ->
                launchOperation { handleAdmissionResult(admission.submitManualAmount()) }

            is PaymentIntent.ManualAmountKeyPress ->
                admission.updateManualAmount(intent.key)

            PaymentIntent.ConfirmPaymentDismiss ->
                if (confirmation.dismiss()) {
                    telemetry.finished(
                        TelemetryPaymentOutcome.Dismissed,
                        TelemetryPaymentError.None
                    )
                    presentation.showActive()
                }

            PaymentIntent.ConfirmPaymentSubmit ->
                launchOperation { handleConfirmationResult(confirmation.submit()) }

            is PaymentIntent.RawInputSubmitted -> {
                startAttempt(TelemetryPaymentSource.Input)
                launchOperation {
                    handleAdmissionResult(
                        admission.handlePaymentInput(rawInput = intent.rawValue)
                    )
                }
            }
        }
    }

    /** Admission ignores input while something else is on screen; so does usage data. */
    private fun startAttempt(source: TelemetryPaymentSource) {
        if (presentation.uiState.value == PaymentUiState.Active) telemetry.started(source)
    }

    private suspend fun handleAdmissionResult(result: AdmissionResult) {
        currentCoroutineContext().ensureActive()
        when (result) {
            AdmissionResult.Presented -> telemetry.presented(presentation.uiState.value)

            is AdmissionResult.Payment -> {
                telemetry.admitted()
                val prepare = RaylTrace.begin(TraceSpan.PaymentPrepare)
                val confirmation = try {
                    confirmation.requestPayment(result.payment)
                } finally {
                    prepare?.end()
                }
                if (confirmation is ConfirmationResult.Execute) telemetry.prepared()
                handleConfirmationResult(confirmation)
            }
        }
    }

    private suspend fun handleConfirmationResult(result: ConfirmationResult<P>) {
        currentCoroutineContext().ensureActive()
        when (result) {
            ConfirmationResult.Presented -> telemetry.presented(presentation.uiState.value)

            is ConfirmationResult.Execute ->
                executePayment(result.payment)

            is ConfirmationResult.AlreadyPaid ->
                finishPayment(
                    result.payment,
                    SettlementOutcome.Paid(feeMsats = 0L, preimage = null, wasAlreadyPaid = true)
                )
        }
    }

    private suspend fun executePayment(payment: P) {
        presentation.showLoading()
        telemetry.submitted()
        val settle = RaylTrace.begin(TraceSpan.PaymentSettle)
        val outcome = try {
            settlement.settle(payment)
        } finally {
            settle?.end()
        }
        currentCoroutineContext().ensureActive()
        finishPayment(payment, outcome)
    }

    private fun finishPayment(payment: SettleablePayment, outcome: SettlementOutcome) {
        val amountMsats = payment.amountOverrideMsats ?: payment.invoice.amount?.msat ?: 0L
        val paymentHash = payment.invoice.paymentHash.toHex()
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
                telemetry.finished(
                    if (outcome.wasAlreadyPaid) {
                        TelemetryPaymentOutcome.AlreadyPaid
                    } else {
                        TelemetryPaymentOutcome.Paid
                    },
                    TelemetryPaymentError.None
                )
            }

            is SettlementOutcome.Failed -> {
                history.record(paymentHash, amountMsats, error = outcome.error)
                presentation.presentError(outcome.error)
                telemetry.finished(TelemetryPaymentOutcome.Failed, outcome.error.telemetryError())
            }

            is SettlementOutcome.Unknown -> {
                presentation.presentError(outcome.error)
                telemetry.finished(TelemetryPaymentOutcome.Unknown, outcome.error.telemetryError())
            }
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
                val error = settlement.errorFor(cause)
                presentation.presentError(error)
                telemetry.failedUnexpectedly(error)
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
