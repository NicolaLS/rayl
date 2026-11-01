package xyz.lilsus.rayl

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.model.WalletStartMode

class RaylSelectionTest {
    @Test
    fun abandonedSetupIsNotRestoredAndOrphanCredentialsAreErasedBeforeChoosing() = runTest {
        ExternalWallet.entries.forEach { wallet ->
            val settings = MapSettings()
            val selection = RaylSelection(settings)
            selection.prepare { true }
            selection.choose(wallet)
            assertEquals(WalletStartMode.Fresh, selection.startMode)
            assertNull(settings.getStringOrNull("rayl.wallet"))

            // The process can die after a provider writes credentials but before success is observed.
            val restarted = RaylSelection(settings)
            assertNotEquals(selection.sessionId, restarted.sessionId)
            val erased = mutableListOf<ExternalWallet>()
            assertNull(restarted.external.value)
            assertFailsWith<IllegalStateException> { restarted.choose(wallet) }
            restarted.prepare {
                erased.add(it)
                true
            }
            assertEquals(ExternalWallet.entries.toList(), erased)
            restarted.choose(wallet)
            assertEquals(WalletStartMode.Fresh, restarted.startMode)
        }
    }

    @Test
    fun onlyTheConfiguredWalletIsRestoredAndOtherCredentialsAreErased() = runTest {
        ExternalWallet.entries.forEach { wallet ->
            val settings = MapSettings()
            val selection = RaylSelection(settings)
            selection.prepare { true }
            selection.choose(wallet)
            selection.connected(wallet)
            val restarted = RaylSelection(settings)
            val erased = mutableListOf<ExternalWallet>()
            restarted.prepare {
                erased.add(it)
                true
            }
            assertEquals(wallet, restarted.external.value)
            assertEquals(WalletStartMode.Restore, restarted.startMode)
            assertEquals(ExternalWallet.entries.filter { it != wallet }, erased)
            assertFailsWith<IllegalStateException> { restarted.choose(wallet) }
        }
    }

    @Test
    fun removalAndSwitchingRequireCleanupAndAlwaysStartFresh() = runTest {
        ExternalWallet.entries.forEach { current ->
            ExternalWallet.entries.forEach { next ->
                val settings = MapSettings()
                val selection = RaylSelection(settings)
                selection.prepare { true }
                selection.choose(current)
                selection.connected(current)
                val previousSession = selection.sessionId
                selection.clear()
                assertNull(RaylSelection(settings).external.value)
                assertFailsWith<IllegalStateException> { selection.choose(next) }
                val erased = mutableListOf<ExternalWallet>()
                selection.prepare {
                    erased.add(it)
                    true
                }
                assertEquals(ExternalWallet.entries.toList(), erased)
                selection.choose(next)
                assertNotEquals(previousSession, selection.sessionId)
                assertEquals(WalletStartMode.Fresh, selection.startMode)
                assertNull(settings.getStringOrNull("rayl.wallet"))
            }
        }
    }

    @Test
    fun failedCleanupBlocksTheChooserAndCanBeRetriedAfterRestart() = runTest {
        val settings = MapSettings()
        val selection = RaylSelection(settings)
        selection.prepare { it != ExternalWallet.Spark }
        assertEquals(WalletPreparation.Failed, selection.preparation.value)
        assertFailsWith<IllegalStateException> { selection.choose(ExternalWallet.Blink) }
        selection.prepare { throw IllegalStateException("secure storage unavailable") }
        assertEquals(WalletPreparation.Failed, selection.preparation.value)
        val restarted = RaylSelection(settings)
        restarted.prepare { true }
        assertEquals(WalletPreparation.Ready, restarted.preparation.value)
        restarted.choose(ExternalWallet.Spark)
    }

    @Test
    fun cancelledCleanupDoesNotReportSuccess() = runTest {
        val selection = RaylSelection(MapSettings())
        assertFailsWith<CancellationException> {
            selection.prepare { throw CancellationException() }
        }
        assertEquals(WalletPreparation.Cleaning, selection.preparation.value)
        selection.prepare { true }
        assertEquals(WalletPreparation.Ready, selection.preparation.value)
    }

    @Test
    fun lateConnectionEventsCannotResurrectARemovedWallet() = runTest {
        val settings = MapSettings()
        val selection = RaylSelection(settings)
        selection.prepare { true }
        selection.choose(ExternalWallet.Spark)
        selection.clear()
        selection.connected(ExternalWallet.Spark)
        assertNull(settings.getStringOrNull("rayl.wallet"))
    }

    @Test
    fun unknownStoredWalletIsNotRestored() = runTest {
        val settings = MapSettings()
        settings.putString("rayl.wallet", "unknown-wallet")
        val selection = RaylSelection(settings)
        val erased = mutableListOf<ExternalWallet>()
        selection.prepare {
            erased.add(it)
            true
        }
        assertNull(selection.external.value)
        assertEquals(ExternalWallet.entries.toList(), erased)
    }

    @Test
    fun raylsWalletIsInUseAfterOnboardingUntilAnExternalWalletConnects() = runTest {
        val settings = MapSettings()
        val selection = RaylSelection(settings)
        assertFalse(usesRaylWallet(settings))
        RaylAppOnboarding(settings).moveTo(AppOnboardingStep.Complete)
        assertNull(selection.external.value)
        assertTrue(usesRaylWallet(settings))

        selection.prepare { true }
        selection.choose(ExternalWallet.Blink)
        // Rayl's wallet stays in use until the external setup succeeds.
        assertTrue(usesRaylWallet(settings))
        selection.connected(ExternalWallet.Blink)
        assertFalse(usesRaylWallet(settings))

        selection.clear()
        assertTrue(usesRaylWallet(settings))
    }

    @Test
    fun restartingRaylsWalletStartsANewSessionOnlyWhileItIsInUse() = runTest {
        val selection = RaylSelection(MapSettings())
        val first = selection.sessionId
        selection.restartRaylWallet()
        assertNotEquals(first, selection.sessionId)

        selection.prepare { true }
        selection.choose(ExternalWallet.Nwc)
        val external = selection.sessionId
        selection.restartRaylWallet()
        assertEquals(external, selection.sessionId)
    }
}
