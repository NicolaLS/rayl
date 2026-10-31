package xyz.lilsus.rayl.providers.rayl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.feature.onboarding.nativeOnboardingText

/**
 * Rayl's wallet onboarding for SwiftUI. [stage] is `checking`, `unavailable`, `agreement`, or
 * `accounts`; [message] is a failure to show, and [isWorking] is set while the passkey is in use.
 */
data class RaylNativeOnboardingSnapshot(
    val stage: String,
    val agreementTitle: String,
    val agreementBody: String,
    val agreementCheckboxTitle: String,
    val agreementNextTitle: String,
    val hasAgreed: Boolean,
    val isWorking: Boolean,
    val message: String?,
    val accountsTitle: String,
    val accountsBody: String,
    val accounts: List<String>,
    val newAccountTitle: String?,
    val connectWalletTitle: String,
    val otherWallets: List<RaylWalletOption>
)

/** Rayl's wallet terms and passkey on iOS; Kotlin keeps the state, SwiftUI renders it. */
class RaylNativeOnboardingController internal constructor(
    private val runtime: RaylRuntime,
    private val otherWallets: () -> List<RaylWalletOption>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val state = runtime.onboardingState

    fun observe(onChange: (RaylNativeOnboardingSnapshot) -> Unit): () -> Unit {
        val job = scope.launch {
            combine(
                state.step,
                state.hasAgreed,
                state.activity,
                runtime.languageRepository.preference
            ) { step, agreed, activity, _ ->
                val common = nativeOnboardingText()
                val accounts = step as? RaylOnboardingStep.Accounts
                RaylNativeOnboardingSnapshot(
                    stage = when (step) {
                        RaylOnboardingStep.Checking, RaylOnboardingStep.Complete -> "checking"
                        RaylOnboardingStep.Unavailable -> "unavailable"
                        RaylOnboardingStep.Agreement -> "agreement"
                        is RaylOnboardingStep.Accounts -> "accounts"
                    },
                    agreementTitle = common.agreementTitle,
                    agreementBody = text("rayl_onboarding_agreement_body"),
                    agreementCheckboxTitle = common.agreementCheckbox,
                    agreementNextTitle = common.agreementContinue,
                    hasAgreed = agreed,
                    isWorking = activity == RaylSetupActivity.Working,
                    message = when (activity) {
                        RaylSetupActivity.Failed -> text("rayl_passkey_failed")
                        RaylSetupActivity.PasskeyWithoutPrf -> text("rayl_passkey_without_prf")
                        RaylSetupActivity.Idle, RaylSetupActivity.Working -> null
                    },
                    accountsTitle = text("rayl_accounts_title"),
                    accountsBody = text("rayl_accounts_body"),
                    accounts = accounts?.accounts.orEmpty(),
                    newAccountTitle = if (accounts?.canCreate == true) {
                        text("rayl_accounts_new")
                    } else {
                        null
                    },
                    connectWalletTitle = text("rayl_connect_wallet"),
                    otherWallets = otherWallets()
                )
            }.collect(onChange)
        }
        return { job.cancel() }
    }

    fun setAgreement(agreed: Boolean) = state.setAgreement(agreed)

    fun accept() = state.accept()

    fun choose(account: String) = state.choose(account)

    fun createAccount() = state.createAccount()

    fun back() = state.back()

    fun useWallet(id: String) {
        runtime.useWallet(id)
    }

    fun clear() {
        scope.coroutineContext[Job]?.cancel()
    }
}
