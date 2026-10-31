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
    val spendingPowerTitle: String,
    val balance: String,
    /** The pocket could not connect; tapping the balance row retries. */
    val pocketFailed: Boolean,
    val addFundsTitle: String,
    val addFundsBody: String,
    val amountLabel: String,
    val createInvoiceTitle: String,
    val copyInvoiceTitle: String,
    val waitingForPayment: String,
    val fundsAdded: String,
    val invoiceFailed: String,
    val doneTitle: String,
    /** `idle`, `creating`, `awaiting`, `received`, or `failed`. */
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
                snapshot(balance, status == PocketStatus.Failed, funding, isSubmitting)
            }.collect(onChange)
        }
        return { job.cancel() }
    }

    fun requestInvoice(amountSats: Long) = runtime.funding.request(amountSats)

    fun resetFunding() = runtime.funding.reset()

    fun retryPocket() = runtime.retryPocket()

    fun useWallet(id: String) {
        runtime.useWallet(id)
    }

    fun erasePocket() = runtime.erase()

    fun clear() {
        scope.coroutineContext[Job]?.cancel()
    }

    private fun snapshot(
        balance: Long?,
        pocketFailed: Boolean,
        funding: FundingState,
        isSubmitting: Boolean
    ) = RaylNativeWalletSettingsSnapshot(
        spendingPowerTitle = text("rayl_spending_power"),
        balance = when {
            pocketFailed -> text("rayl_balance_unavailable")

            balance != null ->
                currentAmountFormatter().format(DisplayAmount(balance, DisplayCurrency.Satoshi))

            else -> text("rayl_balance_updating")
        },
        pocketFailed = pocketFailed,
        addFundsTitle = text("rayl_add_funds"),
        addFundsBody = text("rayl_add_funds_body"),
        amountLabel = text("rayl_amount_sats"),
        createInvoiceTitle = text("rayl_create_invoice"),
        copyInvoiceTitle = text("rayl_copy_invoice"),
        waitingForPayment = text("rayl_waiting_for_payment"),
        fundsAdded = text("rayl_funds_added"),
        invoiceFailed = text("rayl_invoice_failed"),
        doneTitle = text("rayl_done"),
        fundingStage = when (funding) {
            FundingState.Idle -> "idle"
            FundingState.Creating -> "creating"
            is FundingState.Awaiting -> "awaiting"
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
