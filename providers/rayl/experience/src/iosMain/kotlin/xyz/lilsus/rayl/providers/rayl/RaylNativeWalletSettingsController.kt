package xyz.lilsus.rayl.providers.rayl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketStatus
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.ui.format.currentAmountFormatter

data class RaylNativeWalletSettingsSnapshot(
    val balanceTitle: String,
    val balance: String,
    /** The pocket could not connect; tapping the balance row retries. */
    val pocketFailed: Boolean,
    /**
     * The device cannot read its passkey-derived key; tapping the balance row sets the wallet up
     * again.
     */
    val keyUnreadable: Boolean,
    val setUpAgainTitle: String,
    val addFundsTitle: String,
    val addFundsBody: String,
    val openInWalletTitle: String,
    val copyInvoiceTitle: String,
    val copiedTitle: String,
    val waitingForPayment: String,
    val fundsAdded: String,
    /** The amount the last funding payment added, once it arrived. */
    val fundsAddedAmount: String?,
    val invoiceFailed: String,
    val retryTitle: String,
    val doneTitle: String,
    /** `idle`, `creating`, `awaiting` (present [invoice]), `received`, or `failed`. */
    val fundingStage: String,
    val invoice: String?,
    val cancelTitle: String,
    /**
     * This device can't read a key generated without a passkey: only starting a new pocket is a
     * way out, after a confirmation.
     */
    val pocketLost: Boolean,
    val startNewPocketTitle: String,
    val startNewPocketBody: String,
    val startNewPocketConfirmTitle: String,
    val startNewPocketConfirmBody: String,
    val startTitle: String,
    val canErase: Boolean,
    val eraseTitle: String,
    val eraseBody: String,
    val eraseConfirmTitle: String,
    val isSubmitting: Boolean
)

/** The pocket's own rows on its card's page on iOS: the balance, adding funds, recovery. */
class RaylNativeWalletSettingsController internal constructor(private val runtime: RaylRuntime) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun observe(onChange: (RaylNativeWalletSettingsSnapshot) -> Unit): () -> Unit {
        val job = scope.launch {
            combine(
                runtime.pocket.balanceSats,
                runtime.pocket.status,
                runtime.funding.state,
                runtime.paymentCoordinator.isSubmitting,
                combine(
                    runtime.hasRecoveryPhrase,
                    runtime.languageRepository.preference
                ) { hasRecoveryPhrase, _ -> hasRecoveryPhrase }
            ) { balance, status, funding, isSubmitting, hasRecoveryPhrase ->
                snapshot(balance, status, funding, isSubmitting, hasRecoveryPhrase)
            }.collect(onChange)
        }
        return { job.cancel() }
    }

    /** Creates an amountless invoice for the user's other wallet to pay. */
    fun addFunds() = runtime.funding.request()

    fun resetFunding() = runtime.funding.reset()

    fun retryPocket() = runtime.retryPocket()

    fun setUpAgain() = runtime.setUpAgain()

    fun startNewPocket() = runtime.startNewPocket()

    fun erasePocket() = runtime.erase()

    fun clear() {
        scope.coroutineContext[Job]?.cancel()
    }

    private fun snapshot(
        balance: Long?,
        status: PocketStatus,
        funding: FundingState,
        isSubmitting: Boolean,
        hasRecoveryPhrase: Boolean
    ) = RaylNativeWalletSettingsSnapshot(
        balanceTitle = text("rayl_balance"),
        balance = when {
            // Only a passkey can set an unreadable key up again; without one, reading is retried.
            status == PocketStatus.KeyUnreadable && !hasRecoveryPhrase ->
                text("rayl_key_unreadable")

            status == PocketStatus.KeyUnreadable -> text("rayl_balance_unavailable")

            status == PocketStatus.Failed -> text("rayl_balance_unavailable")

            balance != null ->
                currentAmountFormatter().format(DisplayAmount(balance, DisplayCurrency.Satoshi))

            else -> text("rayl_balance_updating")
        },
        pocketFailed = status == PocketStatus.Failed || status == PocketStatus.KeyUnreadable,
        keyUnreadable = status == PocketStatus.KeyUnreadable && !hasRecoveryPhrase,
        setUpAgainTitle = text("rayl_set_up_again"),
        addFundsTitle = text("rayl_add_funds"),
        addFundsBody = text("rayl_add_funds_body"),
        openInWalletTitle = text("rayl_open_in_wallet"),
        copyInvoiceTitle = text("rayl_copy_invoice"),
        copiedTitle = text("rayl_copied"),
        waitingForPayment = text("rayl_waiting_for_payment"),
        fundsAdded = text("rayl_funds_added"),
        fundsAddedAmount = (funding as? FundingState.Received)?.let {
            currentAmountFormatter().format(DisplayAmount(it.amountSats, DisplayCurrency.Satoshi))
        },
        invoiceFailed = text("rayl_invoice_failed"),
        retryTitle = text("rayl_retry"),
        doneTitle = text("rayl_done"),
        fundingStage = when (funding) {
            FundingState.Idle -> "idle"
            FundingState.Creating -> "creating"
            is FundingState.Awaiting -> "awaiting"
            is FundingState.Received -> "received"
            FundingState.Failed -> "failed"
        },
        invoice = (funding as? FundingState.Awaiting)?.invoice,
        cancelTitle = text("rayl_cancel"),
        pocketLost = status == PocketStatus.KeyUnreadable && hasRecoveryPhrase,
        startNewPocketTitle = text("rayl_start_new_pocket"),
        startNewPocketBody = text("rayl_start_new_pocket_body"),
        startNewPocketConfirmTitle = text("rayl_start_new_pocket_confirm"),
        startNewPocketConfirmBody = text("rayl_start_new_pocket_confirm_body"),
        startTitle = text("rayl_start"),
        canErase = runtime.canErase,
        eraseTitle = text("rayl_erase_pocket"),
        eraseBody = text("rayl_erase_pocket_body"),
        eraseConfirmTitle = text("rayl_erase"),
        isSubmitting = isSubmitting
    )
}
