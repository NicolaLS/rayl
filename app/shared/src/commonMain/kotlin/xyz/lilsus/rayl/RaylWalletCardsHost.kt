package xyz.lilsus.rayl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletCard
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletCardChange
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletCardProvider
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletCards
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletCardsHost

/**
 * [RaylCards] as every mounted wallet sees them. [provider] describes a provider in the app's
 * language; [beforeChange] releases the mounted wallet before another one mounts.
 */
internal class RaylWalletCardsHost(
    private val registry: RaylCards,
    scope: CoroutineScope,
    private val provider: (ExternalWallet) -> WalletCardProvider,
    private val clearCard: suspend (RaylCard) -> Boolean,
    /** The pocket's recovery phrase, which only a pocket set up without a passkey has. */
    private val pocketRecoveryPhrase: suspend () -> String?,
    private val beforeChange: () -> Unit = {}
) : WalletCardsHost {
    override val cards: StateFlow<WalletCards> =
        combine(registry.cards, registry.paying) { cards, paying -> snapshot(cards, paying) }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                snapshot(registry.cards.value, registry.paying.value)
            )

    override fun apply(change: WalletCardChange) {
        beforeChange()
        if (!applied(change)) registry.restart()
    }

    private fun applied(change: WalletCardChange): Boolean {
        // A late tap after the wallet changed, or during cleanup, has nothing left to change.
        if (registry.preparation.value != WalletPreparation.Ready ||
            registry.isSettingUp
        ) {
            return false
        }
        when (change) {
            is WalletCardChange.PayWith -> {
                val id = change.id.takeUnless { it == WalletCard.POCKET_ID }
                if (id == registry.paying.value?.id) return false
                if (id != null && registry.cards.value.none { it.id == id }) return false
                registry.payWith(id)
            }

            is WalletCardChange.Add -> {
                val wallet = ExternalWallet.fromId(change.provider) ?: return false
                if (wallet !in registry.addableWallets()) return false
                registry.add(wallet)
            }
        }
        return true
    }

    override suspend fun remove(id: String): Boolean = registry.remove(id, clearCard)

    override fun rename(id: String, name: String) = registry.rename(id, name)

    override suspend fun recoveryPhrase(id: String): String? =
        if (id == WalletCard.POCKET_ID) pocketRecoveryPhrase() else null

    override fun identified(identity: String) = registry.identified(identity)

    override fun reopensAddCard(): Boolean = registry.reopensAddCard()

    override fun reopenedAddCard() = registry.reopenedAddCard()

    private fun snapshot(cards: List<RaylCard>, paying: RaylCard?) = WalletCards(
        // Named in the app's language by the shared screens.
        pocket = WalletCard(WalletCard.POCKET_ID, name = "", provider = "rayl"),
        cards = cards.map { WalletCard(it.id, it.name, it.wallet.id) },
        payingId = paying?.takeIf { it in cards }?.id ?: WalletCard.POCKET_ID,
        addable = registry.addableWallets().map(provider)
    )
}
