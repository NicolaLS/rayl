package xyz.lilsus.raylsuite.feature.walletmanagement

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.core.model.DisplayAmount

/**
 * A card's balance as its wallet reports it, for the balance row at the top of Settings. Cards are
 * funded in their own wallet apps, so it is only shown: it refreshes when Settings opens, after a
 * payment, and when the card connects, not live. [fetch] returns `null` when the wallet does not
 * tell, e.g. an NWC connection without `get_balance`.
 */
class WalletBalance(
    private val scope: CoroutineScope,
    private val fetch: suspend () -> DisplayAmount?
) {
    private val mutableAmount = MutableStateFlow<DisplayAmount?>(null)
    val amount: StateFlow<DisplayAmount?> = mutableAmount.asStateFlow()
    private var job: Job? = null

    /** Fetches the balance again; one fetch at a time, and a failure keeps the last one. */
    fun refresh() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                fetch()?.let { mutableAmount.value = it }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Offline or not connected yet: the next refresh tries again.
            }
        }
    }
}
