package xyz.lilsus.raylsuite.feature.paymentui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import xyz.lilsus.raylsuite.feature.paymentui.R
import xyz.lilsus.raylsuite.feature.paymentui.components.SessionTransactionsScreen

/**
 * This session's payments and their receipts. The selected transaction is hoisted so a result on
 * the Scan tab can open its receipt directly.
 *
 * [onBack] returns to Scan from the list; system back from a receipt first returns to the list.
 */
@Composable
fun PaymentRecentScreen(
    state: PaymentFlowState,
    receiptFeeInformation: String?,
    selectedTransactionId: String?,
    onSelectTransaction: (String?) -> Unit,
    onIntent: (PaymentIntent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val detail = state.sessionItems.firstOrNull { it.id == selectedTransactionId }?.detail

    LaunchedEffect(Unit) {
        onIntent(PaymentIntent.SessionTransactionsOpened)
    }

    NavigationEventHandler(
        state = rememberNavigationEventState(currentInfo = RecentInfo(detail != null)),
        isForwardEnabled = false,
        isBackEnabled = detail != null || onBack != null,
        onBackCompleted = {
            if (detail != null) onSelectTransaction(null) else onBack?.invoke()
        }
    )

    if (detail == null) {
        val transactions = remember(state.sessionItems) {
            state.sessionItems.map(PaymentSessionItem::transaction)
        }
        SessionTransactionsScreen(
            modifier = modifier.fillMaxSize(),
            title = stringResource(R.string.session_transactions_title),
            emptyMessage = stringResource(R.string.session_transactions_empty),
            transactions = transactions,
            onBack = onBack,
            onTransactionSelected = onSelectTransaction
        )
    } else {
        PaymentTransactionDetail(
            detail = detail,
            receiptFeeInformation = receiptFeeInformation,
            onIntent = onIntent,
            onBack = { onSelectTransaction(null) }
        )
    }
}

private data class RecentInfo(val showingDetail: Boolean) : NavigationEventInfo()
