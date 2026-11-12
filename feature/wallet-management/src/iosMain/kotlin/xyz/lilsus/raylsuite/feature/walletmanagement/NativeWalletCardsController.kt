package xyz.lilsus.raylsuite.feature.walletmanagement

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

/** One wallet in the Wallets list; [provider] names its `wallet_<provider>` image. */
data class NativeWalletCardRow(
    val id: String,
    val name: String,
    val provider: String,
    val isPocket: Boolean,
    val isPaying: Boolean,
    /** "Active" for the wallet that pays. */
    val detail: String?
)

// `description` would clash with NSObject's in Swift.
data class NativeWalletCardProvider(val id: String, val title: String, val subtitle: String)

/** The shared wallet block and screens for SwiftUI, in the app's language. */
data class NativeWalletCardsSnapshot(
    val walletsTitle: String,
    /** The active wallet, for the block at the top of Settings. */
    val paying: NativeWalletCardRow,
    /** Rayl first, then the cards the user added. */
    val wallets: List<NativeWalletCardRow>,
    val addTitle: String,
    val addBody: String,
    val addable: List<NativeWalletCardProvider>,
    val activeTitle: String,
    val detailsTitle: String,
    val balanceTitle: String,
    val addFundsTitle: String,
    val removeTitle: String,
    val renameTitle: String,
    val saveTitle: String,
    val cancelTitle: String,
    val recoveryPhraseTitle: String,
    val recoveryPhraseBody: String,
    val copyTitle: String,
    /** Why the paying wallet cannot change right now, e.g. a payment is being submitted. */
    val busyMessage: String,
    val isBusy: Boolean
)

/**
 * Drives the shared wallet block and screens of the mounted wallet on iOS. [onChange] must close that
 * wallet's session before it applies the change to the host; [onRemovePaying] removes the paying
 * card through its provider.
 */
class NativeWalletCardsController(
    private val host: WalletCardsHost,
    private val busy: StateFlow<Boolean>,
    private val languageChanges: Flow<Any?>,
    private val onChange: (WalletCardChange) -> Unit,
    private val onRemovePaying: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun observe(onSnapshot: (NativeWalletCardsSnapshot) -> Unit): () -> Unit {
        val job = scope.launch {
            combine(host.cards, busy, languageChanges) { cards, isBusy, _ ->
                snapshot(cards, isBusy)
            }.collect(onSnapshot)
        }
        return { job.cancel() }
    }

    fun payWith(id: String) {
        if (!busy.value) onChange(WalletCardChange.PayWith(id))
    }

    fun add(provider: String) {
        if (!busy.value) onChange(WalletCardChange.Add(provider))
    }

    /** Removes the active card through its provider; Rayl is never removed. */
    fun removePaying() {
        if (!busy.value && host.cards.value.payingId != WalletCard.POCKET_ID) onRemovePaying()
    }

    fun rename(id: String, name: String) = host.rename(id, name)

    /** See [WalletCardsHost.reopensAddCard]. */
    fun reopensAddCard(): Boolean = host.reopensAddCard()

    fun reopenedAddCard() = host.reopenedAddCard()

    /** The recovery phrase the wallet [id] shows, or `null`; see [WalletCardsHost.recoveryPhrase]. */
    fun recoveryPhrase(id: String, onResult: (String?) -> Unit) {
        scope.launch { onResult(host.recoveryPhrase(id)) }
    }

    fun clear() {
        scope.coroutineContext[Job]?.cancel()
    }

    private fun snapshot(cards: WalletCards, isBusy: Boolean): NativeWalletCardsSnapshot {
        val active = text("wallet_cards_active")
        val pocketName = text("wallet_cards_pocket")
        fun row(card: WalletCard) = NativeWalletCardRow(
            id = card.id,
            name = if (card.isPocket) pocketName else card.name,
            provider = card.provider,
            isPocket = card.isPocket,
            isPaying = card.id == cards.payingId,
            detail = if (card.id == cards.payingId) active else null
        )
        return NativeWalletCardsSnapshot(
            walletsTitle = text("wallet_cards_wallets"),
            paying = row(cards.paying),
            // Rayl is simply the card every user has from the start.
            wallets = (listOf(cards.pocket) + cards.cards).map(::row),
            addTitle = text("wallet_cards_add"),
            addBody = text("wallet_cards_add_body"),
            addable = cards.addable.map {
                NativeWalletCardProvider(it.id, it.title, it.subtitle)
            },
            activeTitle = active,
            detailsTitle = text("wallet_cards_details"),
            balanceTitle = text("wallet_cards_balance"),
            addFundsTitle = text("wallet_cards_add_funds"),
            removeTitle = text("wallet_cards_remove"),
            renameTitle = text("wallet_cards_rename"),
            saveTitle = text("wallet_cards_save"),
            cancelTitle = text("settings_manage_wallet_cancel"),
            recoveryPhraseTitle = text("wallet_cards_recovery_phrase"),
            recoveryPhraseBody = text("wallet_cards_recovery_phrase_body"),
            copyTitle = text("wallet_cards_copy"),
            busyMessage = text("wallet_removal_payment_active"),
            isBusy = isBusy
        )
    }
}

private fun text(key: String): String =
    nativeString(NativeStringResource(table = "WalletManagement", key = key))
