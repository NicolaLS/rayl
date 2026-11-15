package xyz.lilsus.raylsuite.feature.paymentflow

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.lilsus.raylsuite.core.ui.format.AmountFormatter
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedText
import xyz.lilsus.raylsuite.core.ui.resources.LocalizedTextKey
import xyz.lilsus.raylsuite.core.ui.resources.resolve
import xyz.lilsus.raylsuite.feature.paymentui.PaymentFlowState
import xyz.lilsus.raylsuite.feature.paymentui.PaymentLoadingKind
import xyz.lilsus.raylsuite.feature.paymentui.PaymentMessage
import xyz.lilsus.raylsuite.feature.paymentui.PaymentScreenState
import xyz.lilsus.raylsuite.feature.paymentui.PaymentSessionItem
import xyz.lilsus.raylsuite.feature.paymentui.PaymentSessionTransaction
import xyz.lilsus.raylsuite.feature.paymentui.PaymentStatusTone
import xyz.lilsus.raylsuite.feature.paymentui.PaymentTransactionDetail
import xyz.lilsus.raylsuite.feature.paymentui.PaymentUiTextKey
import xyz.lilsus.raylsuite.feature.paymentui.androidStringResource
import xyz.lilsus.raylsuite.feature.paymentui.localizedMessage

/** Maps a provider's own text keys to its Android string resources. */
typealias ProviderStringResource = (LocalizedTextKey) -> Int

/** Projects the payment flow into the shared render state used by the Scan and Recent tabs. */
@Composable
fun rememberPaymentFlowState(
    coordinator: PaymentFlowCoordinator<*>,
    providerStrings: ProviderStringResource
): PaymentFlowState {
    val uiState by coordinator.uiState.collectAsStateWithLifecycle()
    val sessionTransactions by coordinator.sessionTransactions.collectAsStateWithLifecycle()
    val newSessionTransactionCount by
        coordinator.newSessionTransactionCount.collectAsStateWithLifecycle()
    val transactionDetailNavigationTarget by
        coordinator.transactionDetailNavigationTarget.collectAsStateWithLifecycle()
    val formatter = rememberAmountFormatter()

    return PaymentFlowState(
        payment = uiState.toPaymentScreenState(providerStrings),
        sessionItems =
            sessionTransactions.map { transaction ->
                PaymentSessionItem(
                    transaction =
                        transaction.toPaymentSessionTransaction(formatter, providerStrings),
                    detail =
                        PaymentTransactionDetail(
                            id = transaction.id,
                            state =
                                transaction.toDetailUiState()
                                    .toPaymentScreenState(providerStrings),
                            receipt = transaction.receipt
                        )
                )
            },
        newSessionTransactionCount = newSessionTransactionCount,
        transactionDetailNavigationTarget = transactionDetailNavigationTarget
    )
}

/** Localized snackbar text for payment errors and unsupported-input toasts. */
@Composable
fun rememberPaymentMessages(
    coordinator: PaymentFlowCoordinator<*>,
    providerStrings: ProviderStringResource
): Flow<PaymentMessage> {
    val context = LocalContext.current
    return remember(coordinator, context, providerStrings) {
        coordinator.events.map { event ->
            when (event) {
                is PaymentEvent.ShowError ->
                    PaymentMessage(
                        event.error.toLocalizedText().resolveText(context, providerStrings)
                    )

                is PaymentEvent.ShowToast ->
                    PaymentMessage(event.message.localizedMessage(context), rejectsInput = true)
            }
        }
    }
}

@Composable
private fun PaymentUiState.toPaymentScreenState(
    providerStrings: ProviderStringResource
): PaymentScreenState = when (this) {
    PaymentUiState.Active -> PaymentScreenState.Active

    is PaymentUiState.Loading -> PaymentScreenState.Loading(kind.toPresentation())

    is PaymentUiState.EnterAmount -> PaymentScreenState.EnterAmount(entry, lnurlPayDisplay)

    is PaymentUiState.Confirm ->
        PaymentScreenState.Confirm(
            amount = amount,
            lnurlPayDisplay = lnurlPayDisplay,
            fundingSource = fundingSource?.resolveText(providerStrings)
        )

    is PaymentUiState.Success ->
        PaymentScreenState.Success(
            amountPaid = amountPaid,
            feePaid = feePaid,
            wasAlreadyPaid = wasAlreadyPaid
        )

    is PaymentUiState.Error ->
        PaymentScreenState.Error(error.toLocalizedText().resolveText(providerStrings))
}

@Composable
private fun SessionTransactionItem.toPaymentSessionTransaction(
    formatter: AmountFormatter,
    providerStrings: ProviderStringResource
): PaymentSessionTransaction = PaymentSessionTransaction(
    id = id,
    amount = amount,
    statusLabel = LocalizedText(status.labelKey()).resolveText(providerStrings),
    statusTone = status.tone(),
    createdAtMs = createdAtMs,
    supportingText =
        when (status) {
            TransactionStatus.Success -> fee?.let {
                LocalizedText(PaymentUiTextKey.TransactionFee, formatter.format(it))
                    .resolveText(providerStrings)
            }

            TransactionStatus.Failure ->
                (error ?: PaymentUiError.Unexpected()).toLocalizedText()
                    .resolveText(providerStrings)

            TransactionStatus.AlreadyPaid -> null
        }
)

@Composable
private fun LocalizedText.resolveText(providerStrings: ProviderStringResource): String =
    resolve { key -> key.stringResource(providerStrings) }

private fun LocalizedText.resolveText(
    context: Context,
    providerStrings: ProviderStringResource
): String = resolve(context) { key -> key.stringResource(providerStrings) }

@StringRes
private fun LocalizedTextKey.stringResource(providerStrings: ProviderStringResource): Int =
    if (this is PaymentUiTextKey) androidStringResource() else providerStrings(this)

private fun LoadingKind.toPresentation(): PaymentLoadingKind = when (this) {
    LoadingKind.Resolving -> PaymentLoadingKind.Resolving
    LoadingKind.Paying -> PaymentLoadingKind.Paying
}

private fun TransactionStatus.tone(): PaymentStatusTone = when (this) {
    TransactionStatus.Success,
    TransactionStatus.AlreadyPaid -> PaymentStatusTone.Success

    TransactionStatus.Failure -> PaymentStatusTone.Failure
}
