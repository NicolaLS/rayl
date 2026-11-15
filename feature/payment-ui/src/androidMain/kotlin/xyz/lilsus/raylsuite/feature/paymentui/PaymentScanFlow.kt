package xyz.lilsus.raylsuite.feature.paymentui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.Flow
import xyz.lilsus.raylsuite.feature.paymenthub.host.HubSavePrompt
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubIntent

/** The common Scan route, including session history and receipt navigation. */
@Composable
fun PaymentScanFlow(
    state: PaymentFlowState,
    messageEvents: Flow<PaymentMessage>,
    appTitle: String,
    savePrompt: HubSavePrompt?,
    isSubmitting: Boolean,
    selectedTransactionId: String?,
    onSelectTransaction: (String?) -> Unit,
    onIntent: (PaymentIntent) -> Unit,
    onHubIntent: (PaymentHubIntent) -> Unit,
    receiptFeeInformation: String? = null
) {
    var showingRecent by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isSubmitting, state.payment) {
        if (isSubmitting || state.payment is PaymentScreenState.Loading ||
            state.payment is PaymentScreenState.EnterAmount ||
            state.payment is PaymentScreenState.Confirm
        ) {
            onSelectTransaction(null)
            showingRecent = false
        }
    }
    if (showingRecent) {
        PaymentRecentScreen(
            state = state,
            receiptFeeInformation = receiptFeeInformation,
            selectedTransactionId = selectedTransactionId,
            onSelectTransaction = onSelectTransaction,
            onIntent = onIntent,
            onBack = {
                onSelectTransaction(null)
                showingRecent = false
            }
        )
    } else {
        PaymentScanScreen(
            state = state,
            messageEvents = messageEvents,
            appTitle = appTitle,
            savePrompt = savePrompt,
            onIntent = onIntent,
            onHubIntent = onHubIntent,
            onOpenTransaction = { id ->
                if (!isSubmitting) {
                    onSelectTransaction(id)
                    showingRecent = true
                }
            },
            onOpenRecent = if (isSubmitting) null else ({ showingRecent = true })
        )
    }
}
