package xyz.lilsus.rayl.providers.rayl

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketSetup
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketSetupResult

@OptIn(ExperimentalCoroutinesApi::class)
class RaylOnboardingStateTest {
    @Test
    fun theTermsMustBeAcceptedBeforeThePasskey() = runTest {
        val setup = FakeSetup()
        val state = state(setup = setup)
        advanceUntilIdle()

        state.accept()
        advanceUntilIdle()
        assertEquals(RaylOnboardingStep.Agreement, state.step.value)
        assertEquals(0, setup.starts)

        state.setAgreement(true)
        state.accept()
        advanceUntilIdle()
        assertEquals(RaylOnboardingStep.Complete, state.step.value)
        assertEquals(1, setup.starts)
    }

    @Test
    fun completionIsRemembered() = runTest {
        val settings = MapSettings()
        state(settings).apply {
            advanceUntilIdle()
            setAgreement(true)
            accept()
            advanceUntilIdle()
        }

        var created = false
        val next = RaylOnboardingState(settings, this) {
            created = true
            FakeSetup()
        }
        advanceUntilIdle()

        assertEquals(RaylOnboardingStep.Complete, next.step.value)
        assertEquals(false, created)
    }

    @Test
    fun aDeviceWithoutPasskeysSetsTheWalletUpWithoutOne() = runTest {
        val setup = FakeSetup(available = false)
        val settings = MapSettings()
        val state = state(settings, setup)
        advanceUntilIdle()
        assertEquals(RaylOnboardingStep.Agreement, state.step.value)
        assertEquals(true, state.withoutPasskey.value)

        state.setAgreement(true)
        state.accept()
        advanceUntilIdle()

        assertEquals(RaylOnboardingStep.Complete, state.step.value)
        assertEquals(0, setup.starts)
        assertEquals(1, setup.createdWithoutPasskey)
        // Settings shows the recovery phrase on later launches too.
        assertEquals(true, state(settings).withoutPasskey.value)
    }

    @Test
    fun aDeviceWithPasskeysNeverSkipsThePasskeyUnasked() = runTest {
        val setup = FakeSetup(started = PocketSetupResult.Cancelled)
        val state = agreed(setup)

        // Not offered before a password manager failed: this must do nothing.
        state.continueWithoutPasskey()
        advanceUntilIdle()

        assertEquals(0, setup.createdWithoutPasskey)
        assertEquals(false, state.withoutPasskey.value)
    }

    @Test
    fun afterAPasswordManagerWithoutPrfTheUserMaySkipThePasskey() = runTest {
        val setup = FakeSetup(started = PocketSetupResult.PasskeyWithoutPrf)
        val settings = MapSettings()
        val state = agreed(setup, settings)
        assertEquals(true, state.offersWithoutPasskey.value)
        assertEquals(false, state.withoutPasskey.value)

        // Trying another password manager stays possible, and a dismissed prompt keeps the offer.
        setup.started = PocketSetupResult.Cancelled
        state.accept()
        advanceUntilIdle()
        assertEquals(2, setup.starts)
        assertEquals(true, state.offersWithoutPasskey.value)

        state.continueWithoutPasskey()
        advanceUntilIdle()

        assertEquals(RaylOnboardingStep.Complete, state.step.value)
        assertEquals(1, setup.createdWithoutPasskey)
        assertEquals(true, state(settings).withoutPasskey.value)
    }

    @Test
    fun aStoredKeyWithoutAPasskeyKeepsItsRecoveryPhraseAfterAReinstall() = runTest {
        // The iOS Keychain keeps the key across a reinstall, the preferences are gone.
        val setup = FakeSetup(hasKey = true, hasKeyWithoutPasskey = true)
        val state = agreed(setup)

        assertEquals(RaylOnboardingStep.Complete, state.step.value)
        assertEquals(true, state.withoutPasskey.value)
        assertEquals(0, setup.createdWithoutPasskey)
    }

    @Test
    fun aStoredKeyOnlyNeedsTheTerms() = runTest {
        val setup = FakeSetup(available = false, hasKey = true)
        val state = state(setup = setup)
        advanceUntilIdle()

        state.setAgreement(true)
        state.accept()
        advanceUntilIdle()

        assertEquals(RaylOnboardingStep.Complete, state.step.value)
        assertEquals(0, setup.starts)
    }

    @Test
    fun existingAccountsAreChosenFrom() = runTest {
        val setup = FakeSetup(started = PocketSetupResult.ChooseAccount(listOf("Default"), true))
        val state = agreed(setup)

        assertEquals(RaylOnboardingStep.Accounts(listOf("Default"), true), state.step.value)
        state.back()
        assertEquals(RaylOnboardingStep.Agreement, state.step.value)

        state.accept()
        advanceUntilIdle()
        state.choose("Default")
        advanceUntilIdle()

        assertEquals(RaylOnboardingStep.Complete, state.step.value)
        assertEquals(listOf("Default"), setup.chosen)
    }

    @Test
    fun raylsOwnAccountCanBeCreated() = runTest {
        val setup = FakeSetup(started = PocketSetupResult.ChooseAccount(listOf("Default"), true))
        val state = agreed(setup)

        state.createAccount()
        advanceUntilIdle()

        assertEquals(RaylOnboardingStep.Complete, state.step.value)
        assertEquals(1, setup.creates)
    }

    @Test
    fun aFailureIsShownAndCanBeRetried() = runTest {
        val setup = FakeSetup(started = PocketSetupResult.Failed("relays"))
        val state = agreed(setup)
        assertEquals(RaylSetupActivity.Failed, state.activity.value)

        setup.started = PocketSetupResult.Done
        state.accept()
        advanceUntilIdle()

        assertEquals(RaylOnboardingStep.Complete, state.step.value)
        assertEquals(RaylSetupActivity.Idle, state.activity.value)
    }

    @Test
    fun aPasswordManagerWithoutPrfAsksForAnotherOne() = runTest {
        val setup = FakeSetup(started = PocketSetupResult.ChooseAccount(listOf("Default"), true))
        val state = agreed(setup)
        setup.chosenResult = PocketSetupResult.PasskeyWithoutPrf

        state.choose("Default")
        advanceUntilIdle()

        assertEquals(RaylOnboardingStep.Agreement, state.step.value)
        assertEquals(RaylSetupActivity.PasskeyWithoutPrf, state.activity.value)
    }

    @Test
    fun resetRunsTheSetupAgain() = runTest {
        val settings = MapSettings()
        agreed(FakeSetup(available = false), settings).reset()

        val next = state(settings)
        advanceUntilIdle()

        assertEquals(RaylOnboardingStep.Agreement, next.step.value)
        // The erased wallet's origin says nothing about the next one.
        assertEquals(false, next.withoutPasskey.value)
    }

    private fun TestScope.state(settings: MapSettings = MapSettings(), setup: FakeSetup = FakeSetup()) =
        RaylOnboardingState(settings, this) { setup }

    private fun TestScope.agreed(setup: FakeSetup, settings: MapSettings = MapSettings()) = state(settings, setup).apply {
        advanceUntilIdle()
        setAgreement(true)
        accept()
        advanceUntilIdle()
    }
}

private class FakeSetup(
    private val available: Boolean = true,
    private val hasKey: Boolean = false,
    private val hasKeyWithoutPasskey: Boolean = false,
    var started: PocketSetupResult = PocketSetupResult.Done
) : PocketSetup {
    var chosenResult: PocketSetupResult = PocketSetupResult.Done
    var starts = 0
    var creates = 0
    var createdWithoutPasskey = 0
    val chosen = mutableListOf<String>()

    override suspend fun isAvailable() = available

    override suspend fun hasKey() = hasKey

    override suspend fun hasKeyWithoutPasskey() = hasKeyWithoutPasskey

    override suspend fun start(): PocketSetupResult {
        starts++
        return started
    }

    override suspend fun choose(account: String): PocketSetupResult {
        chosen += account
        return chosenResult
    }

    override suspend fun createAccount(): PocketSetupResult {
        creates++
        return PocketSetupResult.Done
    }

    override suspend fun createWithoutPasskey(): PocketSetupResult {
        createdWithoutPasskey++
        return PocketSetupResult.Done
    }

    override fun close() = Unit
}
