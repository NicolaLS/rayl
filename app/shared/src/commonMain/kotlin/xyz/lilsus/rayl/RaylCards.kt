package xyz.lilsus.rayl

import com.russhwolf.settings.Settings
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.lilsus.raylsuite.core.model.WalletStartMode
import xyz.lilsus.raylsuite.core.raylapi.TelemetryOnboardingStep
import xyz.lilsus.raylsuite.core.raylapi.TelemetryWallet
import xyz.lilsus.raylsuite.core.raylapi.TelemetryWalletAction
import xyz.lilsus.raylsuite.core.telemetry.Telemetry

/** Wallet providers whose wallets the user can add as cards. */
internal enum class ExternalWallet(
    /** The brand name a new card is named after. */
    val brand: String
) {
    Blink("Blink"),
    Nwc("NWC"),
    Spark("Spark");

    /** Identifies the provider in platform UI and in card IDs. */
    val id: String get() = name.lowercase()

    companion object {
        fun fromId(id: String): ExternalWallet? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A wallet the user added to pay with besides the pocket. Its credentials and connection data live
 * in storage of its own, so cards never share or see each other's. See SPEC-wallet-lifecycle.
 */
internal data class RaylCard(
    val id: String,
    val wallet: ExternalWallet,
    val name: String,
    /** What the wallet reported identifies it, e.g. its public key; see [RaylCards.identified]. */
    val identity: String? = null
) {
    /**
     * The card with the provider's own ID keeps the storage names Rayl 1.1 used for its single
     * external wallet, so that wallet carries over as this card.
     */
    val usesLegacyStorage: Boolean get() = id == wallet.id

    /** Tells this card's storage apart from other cards of its provider; `null` for legacy. */
    val storageSuffix: String? get() = if (usesLegacyStorage) {
        null
    } else {
        id.removePrefix(
            "${wallet.id}-"
        )
    }

    companion object {
        /** The card whose storage Rayl 1.1 wrote, as cleanup sees it before it is ever added. */
        fun legacy(wallet: ExternalWallet) = RaylCard(wallet.id, wallet, wallet.brand)
    }
}

/**
 * Owns which wallets the user has and which one pays: the pocket, always present and never
 * removable, or one of the cards. Exactly one wallet is mounted at a time. A card being added is
 * not one of [cards] until its setup connects; abandoned setup never comes back and its storage is
 * erased before any wallet mounts. See SPEC-wallet-lifecycle.
 */
internal class RaylCards(
    private val settings: Settings,
    private val reportWalletChange: (TelemetryWallet, TelemetryWalletAction) -> Unit =
        Telemetry::walletChanged
) {
    private val mutableCards = MutableStateFlow(storedCards(settings))

    /** The cards the user added, oldest first. */
    val cards: StateFlow<List<RaylCard>> = mutableCards.asStateFlow()

    private val mutablePaying = MutableStateFlow(
        storedPayingId(settings)?.let { id -> mutableCards.value.firstOrNull { it.id == id } }
    )

    /** The mounted card, maybe still in setup; `null` means the pocket. */
    val paying: StateFlow<RaylCard?> = mutablePaying.asStateFlow()

    /** The card being added, from this process or an abandoned earlier one. */
    private var setup: RaylCard? = settings.getStringOrNull(SETUP_KEY)?.let(::decodeCard)

    /** Where cancelling [setup] goes back to; `null` is the pocket. */
    private var beforeSetup: String? = null

    /** A setup was left by going back: the wallet now mounted reopens Add a card. */
    private var reopenAdd = false

    /** The identity the card in setup reported, before it is one of [cards]. */
    private var setupIdentity: String? = null

    /** A card that finished setup in the current session, until its identity is known. */
    private var addedThisSession: String? = null

    private var pocketIdentity: String? = settings.getStringOrNull(POCKET_IDENTITY_KEY)

    private val mutableNotice = MutableStateFlow<RaylCardNotice?>(null)

    /** Something the user should hear about once, e.g. a card that was already added. */
    val notice: StateFlow<RaylCardNotice?> = mutableNotice.asStateFlow()

    var startMode = WalletStartMode.Restore
        private set

    // Retained through Android rotation, but never restored from an abandoned process/session.
    private val mutableSession = MutableStateFlow(Random.nextLong())

    /** Changes whenever a wallet must mount as a new session. */
    val session: StateFlow<Long> = mutableSession.asStateFlow()
    val sessionId: Long get() = mutableSession.value

    private val preparationMutex = Mutex()
    private val mutablePreparation = MutableStateFlow(WalletPreparation.Cleaning)
    val preparation: StateFlow<WalletPreparation> = mutablePreparation.asStateFlow()

    init {
        // Rayl 1.1 stored one external wallet by provider name: it carries over as a card.
        if (settings.getStringOrNull(CARDS_KEY) == null) {
            val legacy = settings.getStringOrNull(LEGACY_WALLET_KEY)
                ?.let { stored -> ExternalWallet.entries.firstOrNull { it.name == stored } }
                ?.let(RaylCard::legacy)
            if (legacy != null) {
                mutableCards.value = listOf(legacy)
                mutablePaying.value = legacy
            }
            persistCards()
            persistPaying()
            settings.remove(LEGACY_WALLET_KEY)
        }
        Telemetry.wallet = mutablePaying.value?.wallet.telemetryWallet()
    }

    /** Whether the mounted card is still being set up rather than one of [cards]. */
    val isSettingUp: Boolean get() = setup != null && mutablePaying.value?.id == setup?.id

    private fun isPaying(card: RaylCard) = mutablePaying.value?.id == card.id

    /** Providers the user can add a card of: every one, as often as wanted. */
    fun addableWallets(): List<ExternalWallet> = ExternalWallet.entries

    /**
     * Erases what no card owns before any wallet mounts: an abandoned setup, and 1.1's storage
     * when no card uses it. [purgeUnowned] erases platform storage that survives an uninstall and
     * belongs to none of the cards it is given.
     */
    suspend fun prepare(
        clearCard: suspend (RaylCard) -> Boolean,
        purgeUnowned: suspend (List<RaylCard>) -> Boolean = { true }
    ) {
        preparationMutex.withLock {
            if (mutablePreparation.value == WalletPreparation.Ready) return
            mutablePreparation.value = WalletPreparation.Cleaning
            try {
                val live = mutableCards.value + listOfNotNull(setup.takeIf { isSettingUp })
                setup?.takeUnless { isSettingUp }?.let { abandoned ->
                    if (!clearCard(abandoned)) return fail()
                    setup = null
                    settings.remove(SETUP_KEY)
                }
                for (wallet in ExternalWallet.entries) {
                    val legacy = RaylCard.legacy(wallet)
                    if (live.none { it.id == legacy.id } && !clearCard(legacy)) return fail()
                }
                if (!purgeUnowned(live)) return fail()
                mutablePreparation.value = WalletPreparation.Ready
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                fail()
            }
        }
    }

    /** Pays with the pocket (`null`) or the card [id], once the mounted wallet closed. */
    fun payWith(id: String?) {
        checkReady()
        check(!isSettingUp) { "Finish or cancel adding a card first" }
        val card = id?.let { cardId ->
            checkNotNull(mutableCards.value.firstOrNull { it.id == cardId })
        }
        if (card?.id == mutablePaying.value?.id) return
        mount(card, WalletStartMode.Restore)
        persistPaying()
    }

    /** Starts fresh setup of a new card of [wallet], once the mounted wallet closed. */
    fun add(wallet: ExternalWallet) {
        checkReady()
        check(!isSettingUp) { "Finish or cancel adding a card first" }
        val card = newCard(wallet)
        // Persisted first: if the process dies during setup, cleanup still finds this storage.
        settings.putString(SETUP_KEY, encodeCard(card).toString())
        setup = card
        beforeSetup = mutablePaying.value?.id
        mount(card, WalletStartMode.Fresh)
    }

    /**
     * The mounted card connected. Only the first connection of a card being added makes it one
     * of [cards], paying from now on; wallets report their connection on every launch.
     */
    fun connected(card: RaylCard) {
        if (!isPaying(card) || mutablePreparation.value != WalletPreparation.Ready) return
        if (setup?.id != card.id) return
        val added = card.copy(identity = setupIdentity)
        alreadyAdded(added)?.let { existing -> return turnBack(added, existing) }
        mutableCards.value = mutableCards.value + added
        mutablePaying.value = added
        persistCards()
        persistPaying()
        setup = null
        setupIdentity = null
        settings.remove(SETUP_KEY)
        if (added.identity == null) addedThisSession = added.id
        reportWalletChange(card.wallet.telemetryWallet(), TelemetryWalletAction.Connected)
        Telemetry.onboarding(TelemetryOnboardingStep.WalletReady)
    }

    /**
     * The mounted wallet reported [identity], which is the same for every way into one wallet,
     * e.g. its public key. A card being added whose wallet is already the pocket or a card is
     * turned back, and that wallet pays instead.
     */
    fun identified(identity: String) {
        val paying = mutablePaying.value
        when {
            paying == null -> if (pocketIdentity != identity) {
                pocketIdentity = identity
                settings.putString(POCKET_IDENTITY_KEY, identity)
            }

            isSettingUp -> setupIdentity = identity

            else -> {
                val card = mutableCards.value.firstOrNull { it.id == paying.id } ?: return
                if (card.identity == identity) return
                val updated = card.copy(identity = identity)
                if (addedThisSession == card.id) {
                    addedThisSession = null
                    alreadyAdded(updated)?.let { existing -> return turnBack(updated, existing) }
                }
                replace(updated)
            }
        }
    }

    fun dismissNotice() {
        mutableNotice.value = null
    }

    /** Back to the wallet that paid before adding a card; its storage is erased by [prepare]. */
    fun cancelSetup() {
        if (!isSettingUp) return
        val previous = beforeSetup?.let { id -> mutableCards.value.firstOrNull { it.id == id } }
        mutablePreparation.value = WalletPreparation.Cleaning
        mount(previous, WalletStartMode.Restore)
        // Leaving setup is a step back, to the providers it was chosen from.
        reopenAdd = true
    }

    /** Whether the mounted wallet opens Settings at Add a card; see [cancelSetup]. */
    fun reopensAddCard(): Boolean = reopenAdd

    /** The mounted wallet opened Add a card again. */
    fun reopenedAddCard() {
        reopenAdd = false
    }

    /**
     * The mounted card removed itself and erased its storage: the pocket pays. During setup this
     * is the same as cancelling it.
     */
    fun removed(card: RaylCard) {
        if (!isPaying(card)) return
        if (isSettingUp) return cancelSetup()
        drop(card)
        mutablePreparation.value = WalletPreparation.Cleaning
        mount(null, WalletStartMode.Restore)
        persistPaying()
    }

    /** Removes a card that is not paying by erasing its storage; false if that failed. */
    suspend fun remove(id: String, clearCard: suspend (RaylCard) -> Boolean): Boolean {
        val card = mutableCards.value.firstOrNull { it.id == id } ?: return true
        check(!isPaying(card)) { "The paying card removes itself" }
        val cleared = try {
            clearCard(card)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (cleared) drop(card)
        return cleared
    }

    /** Renames the card [id]; a blank name keeps the old one. */
    fun rename(id: String, name: String) {
        val card = mutableCards.value.firstOrNull { it.id == id } ?: return
        val trimmed = name.trim().take(MAX_NAME_LENGTH)
        if (trimmed.isEmpty() || trimmed == card.name) return
        replace(card.copy(name = trimmed))
    }

    /** Mounts a new session of the pocket, e.g. after a debug build erased it. */
    fun restartPocket() {
        if (mutablePaying.value == null) restart()
    }

    /** Mounts a new session of the paying wallet, e.g. after it closed for nothing. */
    fun restart() {
        mutableSession.value = Random.nextLong()
    }

    private fun mount(card: RaylCard?, mode: WalletStartMode) {
        startMode = mode
        addedThisSession = null
        reopenAdd = false
        if (card?.id != setup?.id) setupIdentity = null
        mutableSession.value = Random.nextLong()
        mutablePaying.value = card
        Telemetry.wallet = card?.wallet.telemetryWallet()
    }

    private fun drop(card: RaylCard) {
        mutableCards.value = mutableCards.value.filterNot { it.id == card.id }
        persistCards()
        reportWalletChange(card.wallet.telemetryWallet(), TelemetryWalletAction.Removed)
    }

    private fun replace(card: RaylCard) {
        mutableCards.value = mutableCards.value.map { if (it.id == card.id) card else it }
        if (isPaying(card)) mutablePaying.value = card
        persistCards()
    }

    /** The wallet [card] is already, `null` meaning the pocket; no result means it is new. */
    private fun alreadyAdded(card: RaylCard): RaylCardNotice.AlreadyAdded? {
        val identity = card.identity ?: return null
        if (identity == pocketIdentity) return RaylCardNotice.AlreadyAdded(null)
        val existing =
            mutableCards.value.firstOrNull { it.id != card.id && it.identity == identity }
        return existing?.let { RaylCardNotice.AlreadyAdded(it.id) }
    }

    /** [card] is a wallet the user already has: that one pays instead, and [card]'s storage goes. */
    private fun turnBack(card: RaylCard, existing: RaylCardNotice.AlreadyAdded) {
        if (mutableCards.value.any { it.id == card.id }) drop(card)
        // As abandoned setup, the next cleanup erases its storage.
        settings.putString(SETUP_KEY, encodeCard(card).toString())
        setup = card
        mutableNotice.value = existing
        mutablePreparation.value = WalletPreparation.Cleaning
        val target = existing.cardId?.let { id -> mutableCards.value.firstOrNull { it.id == id } }
        mount(target, WalletStartMode.Restore)
        persistPaying()
    }

    private fun newCard(wallet: ExternalWallet): RaylCard {
        val taken = mutableCards.value.map { it.id }.toSet()
        val id = wallet.id.takeUnless { it in taken }
            ?: generateSequence { "${wallet.id}-${Random.nextLong().toULong().toString(16)}" }
                .first { it !in taken }
        val names = mutableCards.value.filter { it.wallet == wallet }.map { it.name }.toSet()
        val name = wallet.brand.takeUnless { it in names }
            ?: generateSequence(2) { it + 1 }.map { "${wallet.brand} $it" }.first { it !in names }
        return RaylCard(id, wallet, name)
    }

    private fun checkReady() {
        check(mutablePreparation.value == WalletPreparation.Ready) {
            "Finish connection cleanup before changing wallets"
        }
    }

    private fun fail() {
        mutablePreparation.value = WalletPreparation.Failed
    }

    private fun persistCards() {
        settings.putString(
            CARDS_KEY,
            buildJsonArray { mutableCards.value.forEach { add(encodeCard(it)) } }.toString()
        )
    }

    /** Only cards that finished setup are restored on a later launch. */
    private fun persistPaying() {
        val card = mutablePaying.value?.takeIf { paying ->
            mutableCards.value.any { it.id == paying.id }
        }
        if (card == null) settings.remove(PAYING_KEY) else settings.putString(PAYING_KEY, card.id)
    }
}

internal enum class WalletPreparation { Cleaning, Failed, Ready }

internal sealed interface RaylCardNotice {
    /** A card being added was the wallet [cardId] already, or the pocket for `null`. */
    data class AlreadyAdded(val cardId: String?) : RaylCardNotice
}

/** Long enough for any wallet name, short enough for a Settings row. */
private const val MAX_NAME_LENGTH = 40

private const val CARDS_KEY = "rayl.cards"
private const val PAYING_KEY = "rayl.cards.paying"
private const val SETUP_KEY = "rayl.cards.setup"
private const val POCKET_IDENTITY_KEY = "rayl.cards.pocket.identity"

/** Rayl 1.1's key for its single external wallet, by provider name. */
private const val LEGACY_WALLET_KEY = "rayl.wallet"

private fun encodeCard(card: RaylCard): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(card.id))
    put("wallet", JsonPrimitive(card.wallet.id))
    put("name", JsonPrimitive(card.name))
    card.identity?.let { put("identity", JsonPrimitive(it)) }
}

private fun decodeCard(json: String): RaylCard? = runCatching {
    decodeCard(Json.parseToJsonElement(json).jsonObject)
}.getOrNull()

private fun decodeCard(json: JsonObject): RaylCard? {
    val wallet = ExternalWallet.fromId(json["wallet"]?.jsonPrimitive?.content ?: return null)
        ?: return null
    return RaylCard(
        id = json["id"]?.jsonPrimitive?.content ?: return null,
        wallet = wallet,
        name = json["name"]?.jsonPrimitive?.content ?: wallet.brand,
        identity = json["identity"]?.jsonPrimitive?.content
    )
}

/** Unreadable entries are dropped: an unknown provider cannot be mounted. */
private fun storedCards(settings: Settings): List<RaylCard> {
    val stored = settings.getStringOrNull(CARDS_KEY) ?: return emptyList()
    val array = runCatching { Json.parseToJsonElement(stored).jsonArray }.getOrNull()
        ?: JsonArray(emptyList())
    return array.mapNotNull { element ->
        runCatching { decodeCard(element.jsonObject) }.getOrNull()
    }
        .distinctBy { it.id }
}

/** The paying card's ID; `null` is the pocket. Reads Rayl 1.1's key until it is carried over. */
private fun storedPayingId(settings: Settings): String? {
    if (settings.getStringOrNull(CARDS_KEY) != null) {
        return settings.getStringOrNull(PAYING_KEY)
            ?.takeIf { id -> storedCards(settings).any { it.id == id } }
    }
    return settings.getStringOrNull(LEGACY_WALLET_KEY)
        ?.let { stored -> ExternalWallet.entries.firstOrNull { it.name == stored } }
        ?.id
}

/** How usage data names the wallet; `null` is the pocket. */
private fun ExternalWallet?.telemetryWallet(): TelemetryWallet = when (this) {
    null -> TelemetryWallet.Rayl
    ExternalWallet.Blink -> TelemetryWallet.Blink
    ExternalWallet.Nwc -> TelemetryWallet.Nwc
    ExternalWallet.Spark -> TelemetryWallet.Spark
}

/** The pocket pays once Rayl was onboarded and no card does. */
internal fun usesRaylWallet(settings: Settings): Boolean =
    appOnboarded(settings) && storedPayingId(settings) == null
