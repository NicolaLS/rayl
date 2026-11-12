package xyz.lilsus.rayl

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.model.WalletStartMode
import xyz.lilsus.raylsuite.core.raylapi.TelemetryWallet
import xyz.lilsus.raylsuite.core.raylapi.TelemetryWalletAction

class RaylCardsTest {
    @Test
    fun cardsOfOneProviderGetTheirOwnNameAndStorage() = runTest {
        val cards = ready(RaylCards(MapSettings()))
        val first = cards.addConnected(ExternalWallet.Blink)
        val second = cards.addConnected(ExternalWallet.Blink)

        assertEquals(listOf("Blink", "Blink 2"), cards.cards.value.map { it.name })
        // The first keeps 1.1's storage; the second must never read or erase it.
        assertEquals(RAYL_BLINK, first.blinkConfiguration())
        assertNotEquals(first.blinkConfiguration().credentialsName, second.blinkConfiguration().credentialsName)
        assertNotEquals(
            first.blinkConfiguration().walletPreferencesName,
            second.blinkConfiguration().walletPreferencesName
        )
        // Adding a card pays with it.
        assertEquals(second, cards.paying.value)
    }

    @Test
    fun aRemovedNameIsGivenAgainAndTheLegacyStorageToo() = runTest {
        val cards = ready(RaylCards(MapSettings()))
        val first = cards.addConnected(ExternalWallet.Nwc)
        cards.addConnected(ExternalWallet.Nwc)
        cards.payWith(null)
        assertTrue(cards.remove(first.id) { true })

        val third = cards.addConnected(ExternalWallet.Nwc)

        assertEquals("NWC", third.name)
        assertTrue(third.usesLegacyStorage)
    }

    @Test
    fun sparkCardsGetTheirOwnStorageToo() = runTest {
        val cards = ready(RaylCards(MapSettings()))
        val first = cards.addConnected(ExternalWallet.Spark)
        val second = cards.addConnected(ExternalWallet.Spark)

        // The first keeps 1.1's fixed locations; the second has its own.
        assertNull(first.sparkConfiguration().storageName)
        assertNotNull(second.sparkConfiguration().storageName)
        assertEquals(listOf("Spark", "Spark 2"), cards.cards.value.map { it.name })
    }

    @Test
    fun aRenamedCardKeepsItsNameAcrossLaunches() = runTest {
        val settings = MapSettings()
        val cards = ready(RaylCards(settings))
        val card = cards.addConnected(ExternalWallet.Blink)

        cards.rename(card.id, "  Savings  ")
        cards.rename(card.id, " ")

        assertEquals("Savings", cards.paying.value?.name)
        assertEquals(listOf("Savings"), RaylCards(settings).cards.value.map { it.name })
        // Storage follows the ID, never the name.
        assertEquals(RAYL_BLINK, cards.cards.value.single().blinkConfiguration())
    }

    @Test
    fun addingThePocketsWalletAsACardPaysWithThePocketAndErasesTheCard() = runTest {
        val cards = ready(RaylCards(MapSettings()))
        cards.identified("spark:pocket")
        cards.add(ExternalWallet.Spark)
        val inSetup = cards.paying.value!!

        cards.identified("spark:pocket")
        cards.connected(inSetup)

        assertNull(cards.paying.value)
        assertEquals(emptyList(), cards.cards.value)
        assertEquals(RaylCardNotice.AlreadyAdded(null), cards.notice.value)
        val erased = mutableListOf<RaylCard>()
        cards.prepare({
            erased += it
            true
        })
        assertTrue(inSetup.id in erased.map { it.id })
    }

    @Test
    fun aWalletAddedTwiceIsTurnedBackEvenWhenItIdentifiesAfterConnecting() = runTest {
        val settings = MapSettings()
        val cards = ready(RaylCards(settings))
        val first = cards.addConnected(ExternalWallet.Nwc)
        cards.identified("nwc:wallet")
        cards.add(ExternalWallet.Nwc)
        val second = cards.paying.value!!

        // Spark only knows its identity once its SDK is attached, after it reported the connection.
        cards.connected(second)
        cards.identified("nwc:wallet")

        assertEquals(first.id, cards.paying.value?.id)
        assertEquals(listOf(first.id), cards.cards.value.map { it.id })
        assertEquals(RaylCardNotice.AlreadyAdded(first.id), cards.notice.value)
        cards.dismissNotice()
        assertNull(cards.notice.value)
        val erased = mutableListOf<RaylCard>()
        RaylCards(settings).prepare({
            erased += it
            true
        })
        assertTrue(second.id in erased.map { it.id })
        assertFalse(first.id in erased.map { it.id })
    }

    @Test
    fun differentWalletsOfOneProviderAreNotTurnedBack() = runTest {
        val cards = ready(RaylCards(MapSettings()))
        cards.addConnected(ExternalWallet.Spark, identity = "spark:a")
        cards.addConnected(ExternalWallet.Spark, identity = "spark:b")
        // Blink reports no identity: two Blink cards are always two cards.
        cards.addConnected(ExternalWallet.Blink)
        cards.addConnected(ExternalWallet.Blink)

        assertEquals(4, cards.cards.value.size)
        assertNull(cards.notice.value)
    }

    @Test
    fun theWalletThatPaysIsRestoredOnTheNextLaunch() = runTest {
        val settings = MapSettings()
        val cards = ready(RaylCards(settings))
        cards.addConnected(ExternalWallet.Blink)
        val nwc = cards.addConnected(ExternalWallet.Nwc)

        val restarted = RaylCards(settings)

        assertEquals(nwc, restarted.paying.value)
        assertEquals(WalletStartMode.Restore, restarted.startMode)
        assertEquals(2, restarted.cards.value.size)
        assertFalse(usesRaylWallet(settings.onboarded()))
    }

    @Test
    fun abandonedSetupIsNeverRestoredAndItsStorageIsErasedBeforeAnyWalletMounts() = runTest {
        ExternalWallet.entries.forEach { wallet ->
            val settings = MapSettings()
            val cards = ready(RaylCards(settings))
            val before = cards.addConnected(ExternalWallet.Blink)
            cards.add(wallet)
            val inSetup = cards.paying.value!!
            assertEquals(WalletStartMode.Fresh, cards.startMode)

            // The process can die after the provider wrote credentials, before it connected.
            val restarted = RaylCards(settings)
            val erased = mutableListOf<RaylCard>()
            restarted.prepare({
                erased += it
                true
            })

            assertEquals(before, restarted.paying.value, wallet.name)
            assertEquals(listOf(before), restarted.cards.value)
            assertTrue(inSetup in erased, wallet.name)
            assertFalse(before in erased, wallet.name)
        }
    }

    @Test
    fun cancellingSetupGoesBackToTheWalletThatPaidAndErasesTheSetup() = runTest {
        val cards = ready(RaylCards(MapSettings()))
        val nwc = cards.addConnected(ExternalWallet.Nwc)
        cards.add(ExternalWallet.Blink)
        val inSetup = cards.paying.value!!

        // Providers cancel setup by removing what they wrote.
        cards.removed(inSetup)

        assertEquals(nwc, cards.paying.value)
        assertEquals(WalletStartMode.Restore, cards.startMode)
        assertEquals(WalletPreparation.Cleaning, cards.preparation.value)
        val erased = mutableListOf<RaylCard>()
        cards.prepare({
            erased += it
            true
        })
        assertTrue(inSetup in erased)
        assertEquals(listOf(nwc), cards.cards.value)
    }

    @Test
    fun goingBackFromAddingACardReopensAddACardOnce() = runTest {
        val cards = ready(RaylCards(MapSettings()))
        cards.add(ExternalWallet.Blink)
        assertFalse(cards.reopensAddCard())

        cards.cancelSetup()

        assertTrue(cards.reopensAddCard())
        cards.reopenedAddCard()
        assertFalse(cards.reopensAddCard())

        // Any other change of the mounted wallet is not a step back.
        cards.prepare({ true })
        cards.add(ExternalWallet.Nwc)
        cards.cancelSetup()
        cards.prepare({ true })
        cards.addConnected(ExternalWallet.Spark)
        assertFalse(cards.reopensAddCard())
    }

    @Test
    fun lateConnectionEventsCannotAddACancelledCard() = runTest {
        val settings = MapSettings()
        val cards = ready(RaylCards(settings))
        cards.add(ExternalWallet.Spark)
        val inSetup = cards.paying.value!!
        cards.cancelSetup()

        cards.connected(inSetup)

        assertEquals(emptyList(), cards.cards.value)
        assertNull(RaylCards(settings).paying.value)
    }

    @Test
    fun removingThePayingCardPaysWithThePocket() = runTest {
        val settings = MapSettings()
        val cards = ready(RaylCards(settings))
        val blink = cards.addConnected(ExternalWallet.Blink)
        val session = cards.sessionId

        cards.removed(blink)

        assertNull(cards.paying.value)
        assertNotEquals(session, cards.sessionId)
        assertEquals(emptyList(), RaylCards(settings).cards.value)
        assertTrue(usesRaylWallet(settings.onboarded()))
    }

    @Test
    fun removingAnotherCardErasesOnlyItsStorageAndKeepsItWhenThatFails() = runTest {
        val cards = ready(RaylCards(MapSettings()))
        val first = cards.addConnected(ExternalWallet.Blink)
        val second = cards.addConnected(ExternalWallet.Blink)

        assertFalse(cards.remove(first.id) { false })
        assertEquals(listOf(first, second), cards.cards.value)

        val erased = mutableListOf<RaylCard>()
        assertTrue(
            cards.remove(first.id) {
                erased += it
                true
            }
        )
        assertEquals(listOf(first), erased)
        assertEquals(listOf(second), cards.cards.value)
        assertEquals(second, cards.paying.value)
        // The paying card is removed through its provider, which owns its session.
        assertFailsWith<IllegalStateException> { cards.remove(second.id) { true } }
    }

    @Test
    fun legacyStorageIsErasedOnlyWhileNoCardUsesIt() = runTest {
        val settings = MapSettings()
        val erased = mutableListOf<RaylCard>()
        val cards = RaylCards(settings)
        cards.prepare({
            erased += it
            true
        })
        assertEquals(ExternalWallet.entries.map(RaylCard::legacy), erased)

        cards.addConnected(ExternalWallet.Blink)
        cards.addConnected(ExternalWallet.Blink)
        erased.clear()
        RaylCards(settings).prepare({
            erased += it
            true
        }, { purged ->
            // Keychain items of both cards survive the purge on iOS.
            assertEquals(2, purged.size)
            true
        })
        assertEquals(ExternalWallet.entries.filter { it != ExternalWallet.Blink }.map(RaylCard::legacy), erased)
    }

    @Test
    fun failedCleanupBlocksChangesAndCanBeRetried() = runTest {
        val settings = MapSettings()
        val cards = RaylCards(settings)
        cards.prepare({ it.wallet != ExternalWallet.Spark })
        assertEquals(WalletPreparation.Failed, cards.preparation.value)
        assertFailsWith<IllegalStateException> { cards.add(ExternalWallet.Blink) }
        cards.prepare({ throw IllegalStateException("secure storage unavailable") })
        assertEquals(WalletPreparation.Failed, cards.preparation.value)
        cards.prepare({ true }, { false })
        assertEquals(WalletPreparation.Failed, cards.preparation.value)

        val restarted = RaylCards(settings)
        restarted.prepare({ true })
        assertEquals(WalletPreparation.Ready, restarted.preparation.value)
        restarted.add(ExternalWallet.Spark)
    }

    @Test
    fun cancelledCleanupDoesNotReportSuccess() = runTest {
        val cards = RaylCards(MapSettings())
        assertFailsWith<CancellationException> { cards.prepare({ throw CancellationException() }) }
        assertEquals(WalletPreparation.Cleaning, cards.preparation.value)
        cards.prepare({ true })
        assertEquals(WalletPreparation.Ready, cards.preparation.value)
    }

    @Test
    fun usageDataHearsOfACardOnceWhenItConnectsAndOnceWhenItIsRemoved() = runTest {
        val settings = MapSettings()
        val changes = mutableListOf<Pair<TelemetryWallet, TelemetryWalletAction>>()
        val cards = ready(RaylCards(settings) { wallet, action -> changes += wallet to action })

        // A setup that was cancelled never connected, so nothing was removed either.
        cards.add(ExternalWallet.Nwc)
        cards.cancelSetup()
        cards.prepare({ true })
        assertEquals(emptyList(), changes)

        val blink = cards.addConnected(ExternalWallet.Blink)
        // A wallet reports its connection again whenever it reconnects, and on every launch.
        cards.connected(blink)
        val restarted = ready(RaylCards(settings) { wallet, action -> changes += wallet to action })
        restarted.connected(blink)
        restarted.removed(blink)

        assertEquals(
            listOf(
                TelemetryWallet.Blink to TelemetryWalletAction.Connected,
                TelemetryWallet.Blink to TelemetryWalletAction.Removed
            ),
            changes
        )
    }

    @Test
    fun thePocketPaysAfterOnboardingUntilACardFinishesSetup() = runTest {
        val settings = MapSettings()
        val cards = ready(RaylCards(settings))
        assertFalse(usesRaylWallet(settings))
        RaylAppOnboarding(settings).moveTo(AppOnboardingStep.Complete)
        assertTrue(usesRaylWallet(settings))

        cards.add(ExternalWallet.Blink)
        // The pocket starts early on launch until the card's setup succeeds.
        assertTrue(usesRaylWallet(settings))
        cards.connected(cards.paying.value!!)
        assertFalse(usesRaylWallet(settings))

        cards.payWith(null)
        assertTrue(usesRaylWallet(settings))
    }

    @Test
    fun unreadableStoredCardsAreDropped() = runTest {
        val settings = MapSettings(
            "rayl.cards" to """[{"id":"x","wallet":"unknown","name":"X"},{"id":"nwc","wallet":"nwc","name":"NWC"}]""",
            "rayl.cards.paying" to "x"
        )
        val cards = RaylCards(settings)

        assertEquals(listOf(RaylCard.legacy(ExternalWallet.Nwc)), cards.cards.value)
        assertNull(cards.paying.value)
    }

    private suspend fun ready(cards: RaylCards): RaylCards = cards.also { it.prepare({ true }) }

    private fun RaylCards.addConnected(wallet: ExternalWallet, identity: String? = null): RaylCard {
        add(wallet)
        val card = paying.value!!
        identity?.let(::identified)
        connected(card)
        return paying.value!!
    }

    private fun MapSettings.onboarded(): MapSettings = apply { putBoolean("rayl.onboarded", true) }
}
