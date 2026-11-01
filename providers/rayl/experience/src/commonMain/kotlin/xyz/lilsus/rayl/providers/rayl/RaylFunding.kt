package xyz.lilsus.rayl.providers.rayl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketReceipt

/** How an unpaid funding invoice is presented. */
internal enum class FundingPresentation {
    /** The platform should open it as a `lightning:` link in another wallet app. */
    OpenWallet,

    /** It was handed to another wallet app. */
    InWallet,

    /** No wallet app could open it, so Rayl shows it to be scanned or copied. */
    Shown
}

internal sealed interface FundingState {
    data object Idle : FundingState

    data object Creating : FundingState

    data class Awaiting(val invoice: String, val presentation: FundingPresentation) : FundingState

    data class Received(val amountSats: Long) : FundingState

    data object Failed : FundingState
}

/**
 * Adding funds to Rayl's wallet: an amountless invoice that the user's other wallet opens, so the
 * amount is chosen there. Rayl only shows the invoice when no wallet app can take it; Rayl itself
 * does not handle `lightning:` links, so the invoice never comes back to the wallet it funds.
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

    /** Creates a new invoice, also when an earlier one is still unpaid in another wallet. */
    fun request() {
        if (mutableState.value == FundingState.Creating) return
        mutableState.value = FundingState.Creating
        scope.launch {
            mutableState.value = try {
                val invoice = createInvoice(description)
                onInvoice(invoice)
                FundingState.Awaiting(invoice, FundingPresentation.OpenWallet)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                FundingState.Failed
            }
        }
    }

    fun openedInWallet() = present(FundingPresentation.InWallet)

    fun showInvoice() = present(FundingPresentation.Shown)

    fun reset() {
        mutableState.value = FundingState.Idle
    }

    private fun present(presentation: FundingPresentation) {
        val awaiting = mutableState.value as? FundingState.Awaiting ?: return
        mutableState.value = awaiting.copy(presentation = presentation)
    }
}
