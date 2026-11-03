package xyz.lilsus.raylsuite.feature.paymentflow

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedText
import xyz.lilsus.raylsuite.feature.paymentcurrency.PaymentCurrencyManager
import xyz.lilsus.raylsuite.feature.paymentui.LnurlPayDisplay
import xyz.lilsus.raylsuite.feature.paymentui.PaymentConfirmationAmount
import xyz.lilsus.raylsuite.feature.paymentui.PaymentToastMessage
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountUiState

internal class PaymentPresentationPhase(private val currencyManager: PaymentCurrencyManager) {
    private val mutableUiState = MutableStateFlow<PaymentUiState>(PaymentUiState.Active)
    val uiState: StateFlow<PaymentUiState> = mutableUiState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<PaymentEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PaymentEvent> = mutableEvents.asSharedFlow()

    private val mutableTransactionDetailNavigationTarget = MutableStateFlow<String?>(null)
    val transactionDetailNavigationTarget: StateFlow<String?> =
        mutableTransactionDetailNavigationTarget.asStateFlow()

    private val mutableNewSessionTransactionCount = MutableStateFlow(0)
    val newSessionTransactionCount: StateFlow<Int> =
        mutableNewSessionTransactionCount.asStateFlow()

    private val knownTransactionIds = mutableSetOf<String>()
    private val newTransactionIds = mutableSetOf<String>()
    private var lastPaymentResult: CompletedPayment? = null

    fun showActive() {
        mutableUiState.value = PaymentUiState.Active
    }

    fun showLoading(kind: LoadingKind = LoadingKind.Paying) {
        mutableUiState.value = PaymentUiState.Loading(kind)
    }

    fun showManualAmount(entry: ManualAmountUiState, lnurlPayDisplay: LnurlPayDisplay? = null) {
        mutableUiState.value = PaymentUiState.EnterAmount(entry, lnurlPayDisplay)
    }

    fun showConfirmation(
        amount: PaymentConfirmationAmount,
        lnurlPayDisplay: LnurlPayDisplay? = null,
        fundingSource: LocalizedText? = null
    ) {
        mutableUiState.value =
            PaymentUiState.Confirm(
                amount = amount,
                lnurlPayDisplay = lnurlPayDisplay,
                fundingSource = fundingSource
            )
    }

    fun presentError(error: PaymentUiError) {
        mutableUiState.value = PaymentUiState.Error(error)
        emitErrorEvent(error)
    }

    fun emitErrorEvent(error: PaymentUiError) {
        mutableEvents.tryEmit(PaymentEvent.ShowError(error))
    }

    fun showToast(message: PaymentToastMessage) {
        mutableEvents.tryEmit(PaymentEvent.ShowToast(message))
    }

    fun showPaymentSuccess(payment: CompletedPayment) {
        lastPaymentResult = payment
        mutableUiState.value = payment.toUiState()
    }

    fun onSessionTransactionsOpened(currentTransactionIds: Iterable<String>) {
        newTransactionIds.clear()
        knownTransactionIds += currentTransactionIds
        mutableNewSessionTransactionCount.value = 0
    }

    fun updateSessionTransactionIds(currentTransactionIds: Set<String>) {
        knownTransactionIds.retainAll(currentTransactionIds)
        newTransactionIds.retainAll(currentTransactionIds)
        val unseenIds = currentTransactionIds.filterNot(knownTransactionIds::contains)
        if (unseenIds.isNotEmpty()) {
            knownTransactionIds += unseenIds
            newTransactionIds += unseenIds
        }
        mutableNewSessionTransactionCount.value = newTransactionIds.size
    }

    fun markTransactionSeen(id: String) {
        knownTransactionIds += id
        newTransactionIds -= id
        mutableNewSessionTransactionCount.value = newTransactionIds.size
    }

    fun requestTransactionDetailNavigation(id: String) {
        markTransactionSeen(id)
        showActive()
        showTransactionDetail(id)
    }

    fun showTransactionDetail(id: String) {
        mutableTransactionDetailNavigationTarget.value = id
    }

    fun onTransactionDetailNavigationHandled(id: String) {
        if (mutableTransactionDetailNavigationTarget.value == id) {
            mutableTransactionDetailNavigationTarget.value = null
        }
    }

    fun dismissResult() {
        if (mutableUiState.value !is PaymentUiState.Success &&
            mutableUiState.value !is PaymentUiState.Error
        ) {
            return
        }
        mutableTransactionDetailNavigationTarget.value = null
        showActive()
    }

    fun refreshResult() {
        if (mutableUiState.value is PaymentUiState.Success) {
            lastPaymentResult?.let { payment ->
                mutableUiState.value = payment.toUiState()
            }
        }
    }

    fun reset() {
        lastPaymentResult = null
        knownTransactionIds.clear()
        newTransactionIds.clear()
        mutableNewSessionTransactionCount.value = 0
        mutableTransactionDetailNavigationTarget.value = null
        showActive()
    }

    private fun CompletedPayment.toUiState(): PaymentUiState.Success {
        val currencyState = currencyManager.state.value
        return PaymentUiState.Success(
            amountPaid = currencyManager.convertMsatsToDisplay(amountMsats, currencyState),
            feePaid = feeMsats?.let { currencyManager.convertMsatsToDisplay(it, currencyState) },
            wasAlreadyPaid = wasAlreadyPaid
        )
    }
}
