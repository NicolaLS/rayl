package xyz.lilsus.rayl.providers.rayl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketReceipt

internal sealed interface FundingState {
    data object Idle : FundingState

    data object Creating : FundingState

    data class Awaiting(val invoice: String) : FundingState

    data class Received(val amountSats: Long) : FundingState

    data object Failed : FundingState
}

/**
 * Adding funds to Rayl's wallet: an amountless invoice that the user's other wallet pays, so the
 * amount is chosen there. Rayl shows the invoice to be scanned or copied and only hands it to a
 * wallet app when the user asks; Rayl itself does not handle `lightning:` links, so the invoice
 * never comes back to the wallet it funds.
 */
internal class RaylFunding(
    private val createInvoice: suspend (description: String) -> String,
    receipts: Flow<PocketReceipt>,
    private val description: String,
    private val scope: CoroutineScope,
    private val onInvoice: (String) -> Unit = {}
) {
    private val mutableState = MutableStateFlow<FundingState>(FundingState.Idle)
    val state: StateFlow<FundingState> = mutableState.asStateFlow()
    private var creation: Job? = null

    init {
        scope.launch {
            receipts.collect { receipt ->
                val awaiting = mutableState.value as? FundingState.Awaiting
                if (awaiting?.invoice == receipt.invoice) {
                    mutableState.value = FundingState.Received(receipt.amountSats)
                }
            }
        }
    }

    /** Creates a new invoice, also when an earlier one is still unpaid. */
    fun request() {
        if (mutableState.value == FundingState.Creating) return
        mutableState.value = FundingState.Creating
        creation = scope.launch {
            mutableState.value = try {
                val invoice = createInvoice(description)
                onInvoice(invoice)
                FundingState.Awaiting(invoice)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                FundingState.Failed
            }
        }
    }

    /** Ends funding; an invoice still being created is dropped rather than shown later. */
    fun reset() {
        creation?.cancel()
        creation = null
        mutableState.value = FundingState.Idle
    }
}
