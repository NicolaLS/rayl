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
 * its key. Rayl's app onboarding covers everything that is not specific to a wallet.
 */
internal sealed interface RaylOnboardingStep {
    /** Checking whether this device can use passkeys. */
    data object Checking : RaylOnboardingStep

    /** No passkeys on this device, so the user pays with another wallet instead. */
    data object Unavailable : RaylOnboardingStep

    /** The terms; continuing opens or creates the passkey. */
    data object Agreement : RaylOnboardingStep

    /** The passkey already holds [accounts]; the user picks one, or Rayl's own if [canCreate]. */
    data class Accounts(val accounts: List<String>, val canCreate: Boolean) : RaylOnboardingStep

    data object Complete : RaylOnboardingStep
}

internal enum class RaylSetupActivity {
    Idle,
    Working,
    Failed,

    /** The chosen password manager cannot hold Rayl's passkey; continuing lets the user pick another. */
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

    init {
        if (mutableStep.value == RaylOnboardingStep.Checking) {
            scope.launch {
                mutableStep.value = try {
                    hasKey = setup.value.hasKey()
                    if (hasKey || setup.value.isAvailable()) {
                        RaylOnboardingStep.Agreement
                    } else {
                        RaylOnboardingStep.Unavailable
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Continuing reports the failure and can retry.
                    RaylOnboardingStep.Agreement
                }
            }
        }
    }

    fun setAgreement(agreed: Boolean) {
        mutableHasAgreed.value = agreed
    }

    fun accept() {
        if (!mutableHasAgreed.value || mutableStep.value != RaylOnboardingStep.Agreement) return
        if (hasKey) {
            complete()
        } else {
            perform { setup.value.start() }
        }
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
    }

    fun close() {
        if (setup.isInitialized()) setup.value.close()
    }

    private fun perform(action: suspend () -> PocketSetupResult) {
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
                PocketSetupResult.Done -> complete()

                is PocketSetupResult.ChooseAccount ->
                    mutableStep.value =
                        RaylOnboardingStep.Accounts(result.accounts, result.canCreate)

                // Back to the terms, whose Continue lets the user pick another passkey.
                PocketSetupResult.PasskeyWithoutPrf ->
                    mutableStep.value =
                        RaylOnboardingStep.Agreement

                // A dismissed prompt is the user's decision: wait for another tap.
                PocketSetupResult.Cancelled, is PocketSetupResult.Failed -> Unit
            }
        }
    }

    private fun isWorking() = mutableActivity.value == RaylSetupActivity.Working

    private fun complete() {
        settings.putBoolean(KEY_COMPLETE, true)
        mutableStep.value = RaylOnboardingStep.Complete
    }

    private companion object {
        const val KEY_COMPLETE = "rayl.pocket.onboarding.complete"
    }
}
