package xyz.lilsus.rayl.providers.rayl

import com.russhwolf.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketSetup
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketSetupResult

/**
 * What Rayl's wallet needs before its first use: accepting its terms and a passkey that derives
 * its key, or a key generated on this device where no passkey can derive one. Rayl's app
 * onboarding covers everything that is not specific to a wallet.
 */
internal sealed interface RaylOnboardingStep {
    /** Checking whether this device can use passkeys. */
    data object Checking : RaylOnboardingStep

    /** The terms; continuing opens or creates the passkey, or the key where there are no passkeys. */
    data object Agreement : RaylOnboardingStep

    /** The passkey already holds [accounts]; the user picks one, or Rayl's own if [canCreate]. */
    data class Accounts(val accounts: List<String>, val canCreate: Boolean) : RaylOnboardingStep

    data object Complete : RaylOnboardingStep
}

internal enum class RaylSetupActivity {
    Idle,
    Working,
    Failed,

    /**
     * The chosen password manager cannot hold Rayl's passkey; continuing lets the user pick
     * another, and [RaylOnboardingState.continueWithoutPasskey] skips the passkey.
     */
    PasskeyWithoutPrf
}

internal class RaylOnboardingState(
    private val settings: Settings,
    private val scope: CoroutineScope,
    createSetup: () -> PocketSetup
) {
    private val setup = lazy(createSetup)

    /** A key that survived a reinstall (iOS Keychain) needs only the terms. */
    private var hasKey = false

    private val mutableStep = MutableStateFlow<RaylOnboardingStep>(
        if (settings.getBoolean(KEY_COMPLETE, false)) {
            RaylOnboardingStep.Complete
        } else {
            RaylOnboardingStep.Checking
        }
    )
    val step: StateFlow<RaylOnboardingStep> = mutableStep.asStateFlow()

    private val mutableHasAgreed = MutableStateFlow(false)
    val hasAgreed: StateFlow<Boolean> = mutableHasAgreed.asStateFlow()

    private val mutableActivity = MutableStateFlow(RaylSetupActivity.Idle)
    val activity: StateFlow<RaylSetupActivity> = mutableActivity.asStateFlow()

    private val mutableWithoutPasskey =
        MutableStateFlow(settings.getBoolean(KEY_WITHOUT_PASSKEY, false))

    /**
     * Whether the wallet's key is generated on this device instead of derived from a passkey:
     * during setup because the device has no passkeys, afterwards because it was set up that way.
     * Such a wallet has a recovery phrase and no passkey to set it up again.
     */
    val withoutPasskey: StateFlow<Boolean> = mutableWithoutPasskey.asStateFlow()

    private val mutableOffersWithoutPasskey = MutableStateFlow(false)

    /** Set once a password manager could not hold Rayl's passkey: the user may skip the passkey. */
    val offersWithoutPasskey: StateFlow<Boolean> = mutableOffersWithoutPasskey.asStateFlow()

    init {
        if (mutableStep.value == RaylOnboardingStep.Checking) {
            scope.launch {
                try {
                    hasKey = setup.value.hasKey()
                    mutableWithoutPasskey.value = if (hasKey) {
                        setup.value.hasKeyWithoutPasskey()
                    } else {
                        !setup.value.isAvailable()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Continuing reports the failure and can retry.
                }
                mutableStep.value = RaylOnboardingStep.Agreement
            }
        }
    }

    fun setAgreement(agreed: Boolean) {
        mutableHasAgreed.value = agreed
    }

    fun accept() {
        if (!mutableHasAgreed.value || mutableStep.value != RaylOnboardingStep.Agreement) return
        when {
            hasKey -> complete()
            mutableWithoutPasskey.value -> perform { setup.value.createWithoutPasskey() }
            else -> perform { setup.value.start() }
        }
    }

    /** Sets the wallet up without a passkey, once [offersWithoutPasskey]. */
    fun continueWithoutPasskey() {
        if (!mutableHasAgreed.value || mutableStep.value != RaylOnboardingStep.Agreement) return
        if (!mutableOffersWithoutPasskey.value) return
        perform(withoutPasskey = true) { setup.value.createWithoutPasskey() }
    }

    fun choose(account: String) {
        if (mutableStep.value is RaylOnboardingStep.Accounts) {
            perform {
                setup.value.choose(account)
            }
        }
    }

    fun createAccount() {
        if (mutableStep.value is RaylOnboardingStep.Accounts) {
            perform {
                setup.value.createAccount()
            }
        }
    }

    /** From the accounts back to the terms, which ask for the passkey again. */
    fun back() {
        if (mutableStep.value is RaylOnboardingStep.Accounts && !isWorking()) {
            mutableActivity.value = RaylSetupActivity.Idle
            mutableStep.value = RaylOnboardingStep.Agreement
        }
    }

    /** After the pocket was erased, its setup runs again on the next mount. */
    fun reset() {
        settings.remove(KEY_COMPLETE)
        settings.remove(KEY_WITHOUT_PASSKEY)
    }

    fun close() {
        if (setup.isInitialized()) setup.value.close()
    }

    private fun perform(withoutPasskey: Boolean = false, action: suspend () -> PocketSetupResult) {
        if (isWorking()) return
        mutableActivity.value = RaylSetupActivity.Working
        scope.launch {
            val result = try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                PocketSetupResult.Failed(error.message)
            }
            mutableActivity.value = when (result) {
                is PocketSetupResult.Failed -> RaylSetupActivity.Failed
                PocketSetupResult.PasskeyWithoutPrf -> RaylSetupActivity.PasskeyWithoutPrf
                else -> RaylSetupActivity.Idle
            }
            when (result) {
                PocketSetupResult.Done -> {
                    if (withoutPasskey) mutableWithoutPasskey.value = true
                    complete()
                }

                is PocketSetupResult.ChooseAccount ->
                    mutableStep.value =
                        RaylOnboardingStep.Accounts(result.accounts, result.canCreate)

                // Back to the terms, whose Continue lets the user pick another passkey.
                PocketSetupResult.PasskeyWithoutPrf -> {
                    mutableOffersWithoutPasskey.value = true
                    mutableStep.value = RaylOnboardingStep.Agreement
                }

                // A dismissed prompt is the user's decision: wait for another tap.
                PocketSetupResult.Cancelled, is PocketSetupResult.Failed -> Unit
            }
        }
    }

    private fun isWorking() = mutableActivity.value == RaylSetupActivity.Working

    private fun complete() {
        settings.putBoolean(KEY_WITHOUT_PASSKEY, mutableWithoutPasskey.value)
        settings.putBoolean(KEY_COMPLETE, true)
        mutableStep.value = RaylOnboardingStep.Complete
    }

    private companion object {
        const val KEY_COMPLETE = "rayl.pocket.onboarding.complete"
        const val KEY_WITHOUT_PASSKEY = "rayl.pocket.onboarding.without_passkey"
    }
}
