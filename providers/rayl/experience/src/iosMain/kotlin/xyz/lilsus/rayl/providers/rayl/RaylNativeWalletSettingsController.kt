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
    val useAnotherWalletTitle: String,
    val useAnotherWalletBody: String,
    val useAnotherWalletInfo: String,
    /** External wallets the host offers; choosing one closes Rayl's wallet. */
    val otherWallets: List<RaylWalletOption>,
    val cancelTitle: String,
    /** A wallet set up without a passkey: Settings shows its recovery phrase. */
    val hasRecoveryPhrase: Boolean,
    val recoveryPhraseTitle: String,
    val recoveryPhraseBody: String,
    val copyTitle: String,
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

    /** Reads the words of a wallet set up without a passkey; `null` if it has none. */
    fun recoveryPhrase(onResult: (String?) -> Unit) {
        scope.launch { onResult(runtime.recoveryPhrase()) }
    }

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
        useAnotherWalletTitle = text("rayl_use_another_wallet"),
        useAnotherWalletBody = text("rayl_use_another_wallet_body"),
        useAnotherWalletInfo = text("rayl_use_another_wallet_info"),
        otherWallets = otherWallets(),
        cancelTitle = text("rayl_cancel"),
        hasRecoveryPhrase = hasRecoveryPhrase,
        recoveryPhraseTitle = text("rayl_recovery_phrase"),
        recoveryPhraseBody = text("rayl_recovery_phrase_body"),
        copyTitle = text("rayl_copy"),
        canErase = runtime.canErase,
        eraseTitle = text("rayl_erase_pocket"),
        eraseBody = text("rayl_erase_pocket_body"),
        eraseConfirmTitle = text("rayl_erase"),
        isSubmitting = isSubmitting
    )
}
