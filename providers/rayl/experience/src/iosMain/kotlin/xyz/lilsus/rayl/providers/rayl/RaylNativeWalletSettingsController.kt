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
    /** The device cannot read its key; tapping the balance row sets the wallet up again. */
    val keyUnreadable: Boolean,
    val setUpAgainTitle: String,
    val addFundsTitle: String,
    val addFundsBody: String,
    val copyInvoiceTitle: String,
    val waitingForPayment: String,
    val fundsAdded: String,
    /** The amount the last funding payment added, once it arrived. */
    val fundsAddedAmount: String?,
    val invoiceFailed: String,
    val doneTitle: String,
    /**
     * `idle`, `creating`, `open` (open [invoice] in another wallet app, then report back),
     * `inWallet`, `shown` (present [invoice]), `received`, or `failed`.
     */
    val fundingStage: String,
    val invoice: String?,
    val useAnotherWalletTitle: String,
    val useAnotherWalletBody: String,
    val useAnotherWalletInfo: String,
    /** External wallets the host offers; choosing one closes Rayl's wallet. */
    val otherWallets: List<RaylWalletOption>,
    val cancelTitle: String,
    val canErase: Boolean,
    val eraseTitle: String,
    val eraseBody: String,
    val eraseConfirmTitle: String,
    val isSubmitting: Boolean
)

/** Rayl's settings rows on iOS: the balance, adding funds, and using another wallet. */
class RaylNativeWalletSettingsController internal constructor(
    private val runtime: RaylRuntime,
    private val otherWallets: () -> List<RaylWalletOption>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun observe(onChange: (RaylNativeWalletSettingsSnapshot) -> Unit): () -> Unit {
        val job = scope.launch {
            combine(
                runtime.pocket.balanceSats,
                runtime.pocket.status,
                runtime.funding.state,
                runtime.paymentCoordinator.isSubmitting,
                runtime.languageRepository.preference
            ) { balance, status, funding, isSubmitting, _ ->
                snapshot(balance, status, funding, isSubmitting)
            }.collect(onChange)
        }
        return { job.cancel() }
    }

    /** Creates an amountless invoice for the user's other wallet to pay. */
    fun addFunds() = runtime.funding.request()

    fun openedInWallet() = runtime.funding.openedInWallet()

    /** No other wallet app opened the invoice. */
    fun showInvoice() = runtime.funding.showInvoice()

    fun resetFunding() = runtime.funding.reset()

    fun retryPocket() = runtime.retryPocket()

    fun setUpAgain() = runtime.setUpAgain()

    fun useWallet(id: String) {
        runtime.useWallet(id)
    }

    fun erasePocket() = runtime.erase()

    fun clear() {
        scope.coroutineContext[Job]?.cancel()
    }

    private fun snapshot(
        balance: Long?,
        status: PocketStatus,
        funding: FundingState,
        isSubmitting: Boolean
    ) = RaylNativeWalletSettingsSnapshot(
        balanceTitle = text("rayl_balance"),
        balance = when {
            status == PocketStatus.KeyUnreadable -> text("rayl_key_unreadable")

            status == PocketStatus.Failed -> text("rayl_balance_unavailable")

            balance != null ->
                currentAmountFormatter().format(DisplayAmount(balance, DisplayCurrency.Satoshi))

            else -> text("rayl_balance_updating")
        },
        pocketFailed = status == PocketStatus.Failed || status == PocketStatus.KeyUnreadable,
        keyUnreadable = status == PocketStatus.KeyUnreadable,
        setUpAgainTitle = text("rayl_set_up_again"),
        addFundsTitle = text("rayl_add_funds"),
        addFundsBody = text("rayl_add_funds_body"),
        copyInvoiceTitle = text("rayl_copy_invoice"),
        waitingForPayment = text("rayl_waiting_for_payment"),
        fundsAdded = text("rayl_funds_added"),
        fundsAddedAmount = (funding as? FundingState.Received)?.let {
            currentAmountFormatter().format(DisplayAmount(it.amountSats, DisplayCurrency.Satoshi))
        },
        invoiceFailed = text("rayl_invoice_failed"),
        doneTitle = text("rayl_done"),
        fundingStage = when (funding) {
            FundingState.Idle -> "idle"

            FundingState.Creating -> "creating"

            is FundingState.Awaiting -> when (funding.presentation) {
                FundingPresentation.OpenWallet -> "open"
                FundingPresentation.InWallet -> "inWallet"
                FundingPresentation.Shown -> "shown"
            }

            is FundingState.Received -> "received"

            FundingState.Failed -> "failed"
        },
        invoice = (funding as? FundingState.Awaiting)?.invoice,
        useAnotherWalletTitle = text("rayl_use_another_wallet"),
        useAnotherWalletBody = text("rayl_use_another_wallet_body"),
        useAnotherWalletInfo = text("rayl_use_another_wallet_info"),
        otherWallets = otherWallets(),
        cancelTitle = text("rayl_cancel"),
        canErase = runtime.canErase,
        eraseTitle = text("rayl_erase_pocket"),
        eraseBody = text("rayl_erase_pocket_body"),
        eraseConfirmTitle = text("rayl_erase"),
        isSubmitting = isSubmitting
    )
}
