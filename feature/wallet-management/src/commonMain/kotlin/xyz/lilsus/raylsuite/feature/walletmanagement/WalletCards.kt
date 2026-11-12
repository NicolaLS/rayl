package xyz.lilsus.raylsuite.feature.walletmanagement

import kotlinx.coroutines.flow.StateFlow

/** A wallet Rayl can pay with: the pocket ([isPocket]) or a card the user added. */
data class WalletCard(
    val id: String,
    val name: String,
    /** The provider, for its mark: `rayl` for the pocket, else `blink`, `nwc`, or `spark`. */
    val provider: String
) {
    val isPocket: Boolean get() = id == POCKET_ID

    companion object {
        /** The pocket's ID. It is always present and never removed. */
        const val POCKET_ID = "pocket"
    }
}

/** A provider the user can add a card of. */
data class WalletCardProvider(val id: String, val title: String, val subtitle: String)

/** The pocket, the cards the user added, and which one pays. */
data class WalletCards(
    val pocket: WalletCard,
    val cards: List<WalletCard>,
    val payingId: String,
    val addable: List<WalletCardProvider>
) {
    val paying: WalletCard get() = (listOf(pocket) + cards).firstOrNull { it.id == payingId }
        ?: pocket

    fun card(id: String): WalletCard? = (listOf(pocket) + cards).firstOrNull { it.id == id }
}

/** A change of the paying wallet, applied by [WalletCardsHost] once the mounted one closed. */
sealed interface WalletCardChange {
    /** Pay with the card [id], or the pocket. */
    data class PayWith(val id: String) : WalletCardChange

    /** Add a card of [provider]; it pays once its setup connects. */
    data class Add(val provider: String) : WalletCardChange

    /** A string form, so a change survives while the mounted wallet closes across recreation. */
    fun encode(): String = when (this) {
        is PayWith -> "$PAY_WITH$id"
        is Add -> "$ADD$provider"
    }

    companion object {
        private const val PAY_WITH = "pay:"
        private const val ADD = "add:"

        fun decode(value: String): WalletCardChange? = when {
            value.startsWith(PAY_WITH) -> PayWith(value.removePrefix(PAY_WITH))
            value.startsWith(ADD) -> Add(value.removePrefix(ADD))
            else -> null
        }
    }
}

/**
 * The host's cards, as the mounted wallet sees them. Exactly one wallet is mounted: before [apply]
 * the mounted wallet must close its session, which only it can do.
 */
interface WalletCardsHost {
    val cards: StateFlow<WalletCards>

    /**
     * Mounts the wallet [change] asks for instead of the closed one, or mounts the closed one
     * again when the change no longer applies, e.g. after a late tap.
     */
    fun apply(change: WalletCardChange)

    /** Removes a card that is not paying; false if its storage could not be erased. */
    suspend fun remove(id: String): Boolean

    /** Renames the card [id]; the pocket keeps its name. */
    fun rename(id: String, name: String)

    /**
     * The recovery phrase the wallet [id] shows, readable without its session: only a pocket set
     * up without a passkey has one. `null` when there is none.
     */
    suspend fun recoveryPhrase(id: String): String?

    /**
     * The mounted wallet connected and reports what identifies its wallet, the same for every
     * way into it, e.g. a public key. The host turns back a card that is already added.
     */
    fun identified(identity: String)

    /**
     * Whether this wallet mounted because the user went back from adding a card: it then opens
     * Settings at Add a card, as if that setup were a step pushed onto it. Asking does not change
     * it; [reopenedAddCard] does, once Add a card is open.
     */
    fun reopensAddCard(): Boolean

    fun reopenedAddCard()
}
