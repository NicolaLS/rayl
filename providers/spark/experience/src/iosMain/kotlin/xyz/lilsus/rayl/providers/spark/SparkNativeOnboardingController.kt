package xyz.lilsus.rayl.providers.spark

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.spark.application.wallet.CredentialProblemKind
import xyz.lilsus.rayl.providers.spark.application.wallet.RecoveryPhraseWords
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletAccess
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletAccessState
import xyz.lilsus.rayl.providers.spark.feature.onboarding.nativeSparkOnboardingText
import xyz.lilsus.rayl.providers.spark.feature.walletconnection.WalletAction
import xyz.lilsus.rayl.providers.spark.feature.walletconnection.WalletMessage
import xyz.lilsus.rayl.providers.spark.feature.walletconnection.WalletViewModel
import xyz.lilsus.rayl.providers.spark.feature.walletconnection.nativeSparkWalletConnectionText
import xyz.lilsus.raylsuite.core.ui.NativeCredentialText
import xyz.lilsus.raylsuite.core.ui.nativeCredentialText
import xyz.lilsus.raylsuite.core.ui.platform.CredentialClipboard
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingViewModel
import xyz.lilsus.raylsuite.feature.onboarding.nativeOnboardingText

data class SparkNativeOnboardingSnapshot(
    val step: String,
    val credentialText: NativeCredentialText,
    val privacyPolicyUrl: String?,
    val termsUrl: String?,
    val canCancel: Boolean,
    val backTitle: String,
    val agreementTitle: String,
    val agreementBody: String,
    val agreementCheckboxTitle: String,
    val agreementNextTitle: String,
    val hasAgreed: Boolean,
    val connectWalletTitle: String,
    val walletKind: String,
    val walletTitle: String,
    val walletBody: String,
    val phraseLabel: String,
    val passphraseToggle: String,
    val passphraseLabel: String,
    val passphraseHint: String,
    val passphraseNote: String,
    val importTitle: String,
    /** The recovery phrase, one entry per numbered field. */
    val words: List<String>,
    /** Per field: no BIP-39 word starts with what was typed. */
    val wrongWords: List<Boolean>,
    /** Per field: the rest of the only word it can become, shown inline and accepted on Return. */
    val wordCompletions: List<String>,
    val wordLabels: List<String>,
    /** The offered phrase lengths and their labels, e.g. 12 and "12 words". */
    val wordCounts: List<Int>,
    val wordCountTitles: List<String>,
    val wordsWrong: String,
    /** The optional BIP-39 passphrase, kept exactly as typed. */
    val passphrase: String,
    val canImport: Boolean,
    val walletStatus: String?,
    val walletError: String?,
    val retryTitle: String,
    val resetTitle: String,
    val confirmRemoval: Boolean,
    val removalTitle: String,
    val removalBody: String,
    val removalConfirmTitle: String,
    val cancelTitle: String
)

class SparkNativeOnboardingController internal constructor(
    private val onboarding: OnboardingViewModel,
    walletAccess: WalletAccess,
    languageChanges: Flow<*>,
    private val appName: String,
    private val connectionOnly: Boolean = false,
    private val onComplete: () -> Unit = {},
    private val canCancelSetup: () -> Boolean = { false },
    private val onCancelSetup: () -> Unit = {},
    private val privacyPolicyUrl: String? = null,
    private val termsUrl: String? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var closed = false
    private val wallet = WalletViewModel(walletAccess)
    private val clipboard = CredentialClipboard()
    private val snapshot = MutableStateFlow<SparkNativeOnboardingSnapshot?>(null)
    private var resolvingInitialWallet = walletAccess.state.value.isInitialising()
    private var currentStep =
        if (walletAccess.state.value == WalletAccessState.Connected &&
            !connectionOnly
        ) {
            STEP_AGREEMENT
        } else {
            STEP_WALLET
        }
    private var resetRequested = false
    private var wasConnected = walletAccess.state.value == WalletAccessState.Connected

    init {
        scope.launch {
            combine(onboarding.uiState, wallet.state, languageChanges) { _, _, _ -> Unit }
                .collect { publishSnapshot() }
        }
    }

    fun observe(onChange: (SparkNativeOnboardingSnapshot) -> Unit): () -> Unit {
        val job = scope.launch { snapshot.filterNotNull().collect(onChange) }
        return { job.cancel() }
    }

    fun clear() {
        closed = true
        scope.cancel()
        if (wallet.state.value.access == WalletAccessState.Connected) clipboard.clearAfterSaving()
        wallet.dispose()
        clipboard.discard()
        snapshot.value = null
    }

    fun setAgreement(agreed: Boolean) {
        if (closed) return
        onboarding.setAgreement(agreed)
    }

    fun continueAgreement() {
        if (!closed && onboarding.uiState.value.hasAgreed) onComplete()
    }

    fun showWalletConnection() = moveTo(STEP_WALLET)

    fun updateWord(index: Int, value: String) {
        if (closed || wallet.state.value.access != WalletAccessState.NoWallet) return
        wallet.dispatch(WalletAction.WordChanged(index, value))
    }

    /** Accepts the only word field [index] can become, e.g. on Return or when leaving it. */
    fun completeWord(index: Int) {
        if (closed || wallet.state.value.access != WalletAccessState.NoWallet) return
        wallet.dispatch(WalletAction.WordCompleted(index))
    }

    fun setWordCount(count: Int) {
        if (closed || wallet.state.value.access != WalletAccessState.NoWallet) return
        wallet.dispatch(WalletAction.WordCountChanged(count))
    }

    fun pasteRecoveryPhrase() {
        if (closed || wallet.state.value.access != WalletAccessState.NoWallet) return
        clipboard.read()?.takeIf(String::isNotBlank)?.let {
            wallet.dispatch(WalletAction.RecoveryPhraseChanged(it))
        }
    }

    fun updatePassphrase(value: String) {
        if (closed || wallet.state.value.access != WalletAccessState.NoWallet) return
        wallet.dispatch(WalletAction.PassphraseChanged(value))
    }

    /** Kept exactly as copied: spaces are part of a passphrase. */
    fun pastePassphrase() {
        if (closed || wallet.state.value.access != WalletAccessState.NoWallet) return
        clipboard.read()?.takeIf(String::isNotEmpty)?.let {
            wallet.dispatch(WalletAction.PassphraseChanged(it))
        }
    }

    fun importWallet() {
        wallet.dispatch(WalletAction.Import)
    }

    fun retryWallet() {
        wallet.dispatch(WalletAction.Retry)
    }

    fun requestReset() {
        wallet.dispatch(WalletAction.RequestRemoval)
    }

    fun cancelReset() {
        wallet.dispatch(WalletAction.CancelRemoval)
    }

    fun confirmReset() {
        resetRequested = true
        wallet.dispatch(WalletAction.ConfirmRemoval)
    }

    fun cancelSetup() {
        if (!closed && canCancelSetup()) onCancelSetup()
    }

    private fun moveTo(step: String) {
        if (closed) return
        currentStep = step
        scope.launch { publishSnapshot() }
    }

    private suspend fun publishSnapshot() {
        val state = onboarding.uiState.value
        val walletState = wallet.state.value
        if (resolvingInitialWallet && !walletState.access.isInitialising()) {
            resolvingInitialWallet = false
            currentStep =
                if (walletState.access == WalletAccessState.Connected &&
                    !connectionOnly
                ) {
                    STEP_AGREEMENT
                } else {
                    STEP_WALLET
                }
        }
        if (resetRequested && walletState.access == WalletAccessState.NoWallet) {
            resetRequested = false
            currentStep = STEP_WALLET
        }

        val connected = walletState.access == WalletAccessState.Connected
        if (connected && !wasConnected) {
            clipboard.clearAfterSaving()
            currentStep = if (connectionOnly) STEP_WALLET else STEP_AGREEMENT
            if (connectionOnly) onComplete()
        }
        wasConnected = connected

        val common = nativeOnboardingText()
        val spark = nativeSparkOnboardingText(appName)
        val walletText = nativeSparkWalletConnectionText(appName)
        val step = effectiveStep(walletState.access)
        val recovery = recoveryCopy(walletState.access, walletText)

        snapshot.value =
            SparkNativeOnboardingSnapshot(
                step = step,
                credentialText = nativeCredentialText(),
                privacyPolicyUrl = privacyPolicyUrl,
                termsUrl = termsUrl,
                canCancel = canCancelSetup(),
                backTitle = common.back,
                agreementTitle = common.agreementTitle,
                agreementBody = spark.agreementBody,
                agreementCheckboxTitle = common.agreementCheckbox,
                agreementNextTitle = common.agreementContinue,
                hasAgreed = state.hasAgreed,
                connectWalletTitle = walletText.importAction,
                walletKind = walletKind(walletState.access),
                walletTitle = recovery?.first ?: walletText.importTitle,
                walletBody = recovery?.second ?: walletText.importBody,
                phraseLabel = walletText.phraseLabel,
                passphraseToggle = walletText.passphraseToggle,
                passphraseLabel = walletText.passphraseLabel,
                passphraseHint = walletText.passphraseHint,
                passphraseNote = walletText.passphraseNote,
                importTitle = walletText.importAction,
                words = walletState.words,
                wrongWords = walletState.words.indices.map {
                    walletState.isWordWrong(it) ||
                        (walletState.words[it].isNotEmpty() && !walletState.isWordComplete(it))
                },
                wordCompletions = walletState.words.indices.map(walletState::completionSuffix),
                wordLabels = walletText.wordLabels,
                wordCounts = RecoveryPhraseWords.OFFERED_COUNTS,
                wordCountTitles = walletText.wordCountOptions,
                wordsWrong = walletText.wordsWrong,
                passphrase = walletState.passphrase,
                canImport = walletState.canImport,
                walletStatus = walletStatus(walletState.access, walletText),
                walletError = walletState.message?.message(walletText),
                retryTitle = walletText.retry,
                resetTitle = walletText.resetAction,
                confirmRemoval = walletState.confirmRemoval,
                removalTitle = walletText.removeTitle,
                removalBody = walletText.removeBody,
                removalConfirmTitle = walletText.removeConfirm,
                cancelTitle = walletText.cancel
            )
    }

    private fun effectiveStep(access: WalletAccessState): String =
        if (isRecovery(access)) STEP_WALLET else currentStep

    private fun isRecovery(access: WalletAccessState): Boolean = access.isInitialising() ||
        access == WalletAccessState.ReconnectRequired ||
        access == WalletAccessState.ResetRequired ||
        access is WalletAccessState.CredentialProblem ||
        access == WalletAccessState.Removing

    private companion object {
        const val STEP_AGREEMENT = "agreement"
        const val STEP_WALLET = "wallet"
    }
}

private fun WalletAccessState.isInitialising(): Boolean =
    this == WalletAccessState.Loading || this == WalletAccessState.Connecting

private fun walletKind(access: WalletAccessState): String = when (access) {
    WalletAccessState.NoWallet -> "import"

    WalletAccessState.Connecting -> "connecting"

    WalletAccessState.Loading,
    WalletAccessState.Removing -> "progress"

    WalletAccessState.Connected -> "connected"

    else -> "recovery"
}

private fun walletStatus(
    access: WalletAccessState,
    text: xyz.lilsus.rayl.providers.spark.feature.walletconnection.NativeSparkWalletConnectionText
): String? = when (access) {
    WalletAccessState.Loading -> text.loading
    WalletAccessState.Connecting -> text.connecting
    WalletAccessState.Removing -> text.removing
    WalletAccessState.Connected -> null
    else -> null
}

private fun recoveryCopy(
    access: WalletAccessState,
    text: xyz.lilsus.rayl.providers.spark.feature.walletconnection.NativeSparkWalletConnectionText
): Pair<String, String>? = when (access) {
    WalletAccessState.ReconnectRequired -> text.reconnectTitle to text.reconnectBody

    WalletAccessState.ResetRequired -> text.resetTitle to text.resetBody

    is WalletAccessState.CredentialProblem ->
        text.credentialTitle to
            when (access.kind) {
                CredentialProblemKind.UNAVAILABLE -> text.credentialUnavailable
                CredentialProblemKind.INVALIDATED -> text.credentialInvalidated
                CredentialProblemKind.CORRUPT -> text.credentialCorrupt
            }

    else -> null
}

private fun WalletMessage.message(
    text: xyz.lilsus.rayl.providers.spark.feature.walletconnection.NativeSparkWalletConnectionText
): String = when (this) {
    WalletMessage.ALREADY_CONFIGURED -> text.errorAlreadyConfigured
    WalletMessage.INVALID_MNEMONIC -> text.errorInvalidMnemonic
    WalletMessage.CONNECTION_FAILED -> text.errorConnection
    WalletMessage.CREDENTIAL_STORE_FAILED -> text.errorStorage
    WalletMessage.RESET_REQUIRED -> text.errorReset
}
