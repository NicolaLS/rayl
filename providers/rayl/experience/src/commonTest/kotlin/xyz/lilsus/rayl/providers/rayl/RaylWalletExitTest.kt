package xyz.lilsus.rayl.providers.rayl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** Leaving Rayl's wallet, SPEC-wallet-lifecycle "The Rayl pocket" (PR-09). */
class RaylWalletExitTest {
    @Test
    fun usingAnotherWalletClosesThePocketButKeepsItsKey() = runTest {
        val pocket = FakePocket()
        val exit = exit(pocket)

        assertTrue(exit.useWallet("blink"))

        assertEquals(RaylExit.Done, exit.state.value)
        assertEquals(RaylExitTarget.Wallet("blink"), exit.target)
        assertEquals(listOf("stop session", "close"), pocket.calls)
    }

    @Test
    fun onlyADebugBuildCanEraseTheKey() = runTest {
        val release = FakePocket()
        exit(release, canErase = false).erase()
        assertEquals(emptyList(), release.calls)

        val debug = FakePocket()
        val exit = exit(debug, canErase = true)
        exit.erase()
        assertEquals(listOf("stop session", "erase"), debug.calls)
        assertEquals(RaylExitTarget.Erased, exit.target)
    }

    @Test
    fun settingUpAgainErasesTheKeyOnlyWhenItCannotBeRead() = runTest {
        // In every build, the one way to replace a key copy is when the device can't read it.
        val readable = FakePocket(keyUnreadable = false)
        exit(readable).setUpAgain()
        assertEquals(emptyList(), readable.calls)

        val unreadable = FakePocket(keyUnreadable = true)
        exit(unreadable).setUpAgain()
        assertEquals(listOf("stop session", "erase"), unreadable.calls)
    }

    @Test
    fun aKeyGeneratedWithoutAPasskeyIsNeverErasedToSetUpAgain() = runTest {
        // No passkey could derive that wallet again: erasing its unreadable key would lose the
        // funds for good, while a later read may still succeed.
        val pocket = FakePocket(keyUnreadable = true, withoutPasskey = true)
        val exit = exit(pocket)

        exit.setUpAgain()

        assertEquals(RaylExit.None, exit.state.value)
        assertEquals(emptyList(), pocket.calls)
    }

    @Test
    fun aNewPocketIsStartedOnlyForAnUnreadableKeyWithoutAPasskey() = runTest {
        // Its only copy goes: never for a readable key, nor for one a passkey can derive again.
        listOf(
            FakePocket(keyUnreadable = false, withoutPasskey = true),
            FakePocket(keyUnreadable = true, withoutPasskey = false)
        ).forEach { pocket ->
            exit(pocket).startNewPocket()
            assertEquals(emptyList(), pocket.calls)
        }

        val lost = FakePocket(keyUnreadable = true, withoutPasskey = true)
        val exit = exit(lost)
        exit.startNewPocket()

        assertEquals(listOf("stop session", "erase"), lost.calls)
        assertEquals(RaylExitTarget.Erased, exit.target)
    }

    @Test
    fun theWalletCannotBeLeftWhileAPaymentIsBeingSubmitted() = runTest {
        // Leaving resets the payment session; mid-payment that would lose its outcome (PR-04).
        val pocket = FakePocket(keyUnreadable = true)
        val exit = exit(pocket, canErase = true, busy = true)

        assertFalse(exit.useWallet("nwc"))
        exit.erase()
        exit.setUpAgain()

        assertEquals(RaylExit.None, exit.state.value)
        assertEquals(emptyList(), pocket.calls)
    }

    @Test
    fun aSecondRequestWhileLeavingIsIgnored() = runTest {
        // E.g. a double tap on "Use another wallet", then "Erase" before the pocket has closed.
        val closing = CompletableDeferred<Unit>()
        val pocket = FakePocket(closing = closing)
        val exit = exit(pocket, canErase = true)

        exit.useWallet("blink")
        assertEquals(RaylExit.Working, exit.state.value)
        assertFalse(exit.useWallet("nwc"))
        exit.erase()
        closing.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(RaylExit.Done, exit.state.value)
        assertEquals(RaylExitTarget.Wallet("blink"), exit.target)
        assertEquals(listOf("stop session", "close"), pocket.calls)
    }

    @Test
    fun aFailedEraseCanBeRetriedAndIsNotReportedAsDone() = runTest {
        val pocket = FakePocket(eraseSucceeds = false)
        val exit = exit(pocket, canErase = true)

        exit.erase()
        assertEquals(RaylExit.Failed, exit.state.value)

        pocket.eraseSucceeds = true
        exit.retry()
        assertEquals(RaylExit.Done, exit.state.value)
        assertEquals(RaylExitTarget.Erased, exit.target)
    }

    private fun TestScope.exit(pocket: FakePocket, canErase: Boolean = false, busy: Boolean = false) = RaylWalletExit(
        scope = backgroundScope,
        canErase = canErase,
        keyUnreadable = { pocket.keyUnreadable },
        withoutPasskey = { pocket.withoutPasskey },
        isBusy = { busy },
        stopSession = { pocket.calls += "stop session" },
        closePocket = {
            pocket.closing?.await()
            pocket.calls += "close"
        },
        erasePocket = {
            pocket.calls += "erase"
            pocket.eraseSucceeds
        }
    )
}

private class FakePocket(
    val keyUnreadable: Boolean = false,
    val withoutPasskey: Boolean = false,
    var eraseSucceeds: Boolean = true,
    val closing: CompletableDeferred<Unit>? = null
) {
    val calls = mutableListOf<String>()
}
