package xyz.lilsus.rayl.providers.spark.feature.walletconnection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.lilsus.rayl.providers.spark.application.wallet.ImportWalletResult
import xyz.lilsus.rayl.providers.spark.application.wallet.RecoveryPhraseWords
import xyz.lilsus.rayl.providers.spark.application.wallet.RemoveWalletResult
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletAccess
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletAccessState

data class WalletUiState(
    val access: WalletAccessState = WalletAccessState.Loading,
    /** The recovery phrase, one entry per numbered field. */
    val words: List<String> = blankWords(RecoveryPhraseWords.OFFERED_COUNTS.first()),
    /** The optional BIP-39 passphrase, kept exactly as typed. */
    val passphrase: String = "",
    val message: WalletMessage? = null,
    val confirmRemoval: Boolean = false
) {
    val recoveryPhrase: String get() = words.joinToString(" ")

    /** Every field holds a BIP-39 word; the checksum is verified on import. */
    val isPhraseComplete: Boolean get() = words.all(RecoveryPhraseWords::isWord)

    val canImport: Boolean get() = access == WalletAccessState.NoWallet && isPhraseComplete

    override fun toString(): String =
        "WalletUiState(access=$access, words=<${words.size} redacted>, passphrase=<redacted>, " +
            "message=$message, confirmRemoval=$confirmRemoval)"

    /** A word no BIP-39 word starts with, so it is wrong however it continues. */
    fun isWordWrong(index: Int): Boolean =
        words[index].isNotEmpty() && !RecoveryPhraseWords.isPrefix(words[index])

    /** Field [index] holds a word, or text that can only become one. */
    fun isWordComplete(index: Int): Boolean = RecoveryPhraseWords.isWord(words[index]) ||
        RecoveryPhraseWords.completion(words[index]) != null

    /** The rest of the only word that field [index] can become, for showing inline. */
    fun completionSuffix(index: Int): String =
        RecoveryPhraseWords.completion(words[index])?.removePrefix(words[index]).orEmpty()
}

private fun blankWords(count: Int) = List(count) { "" }

enum class WalletMessage {
    ALREADY_CONFIGURED,
    INVALID_MNEMONIC,
    CONNECTION_FAILED,
    CREDENTIAL_STORE_FAILED,
    RESET_REQUIRED
}

sealed interface WalletAction {
    /** Replaces the whole phrase, e.g. pasted; empty clears it. */
    data class RecoveryPhraseChanged(val value: String) : WalletAction {
        override fun toString(): String = "RecoveryPhraseChanged(<redacted>)"
    }

    /** Text typed into one numbered field; several words fill the following fields. */
    data class WordChanged(val index: Int, val value: String) : WalletAction {
        override fun toString(): String = "WordChanged(index=$index, value=<redacted>)"
    }

    /** Accepts the only word field [index] can become, e.g. when moving to the next field. */
    data class WordCompleted(val index: Int) : WalletAction
    data class WordCountChanged(val count: Int) : WalletAction
    data class PassphraseChanged(val value: String) : WalletAction {
        override fun toString(): String = "PassphraseChanged(<redacted>)"
    }
    data object Import : WalletAction
    data object Retry : WalletAction
    data object RequestRemoval : WalletAction
    data object CancelRemoval : WalletAction
    data object ConfirmRemoval : WalletAction
}

class WalletViewModel(private val walletAccess: WalletAccess) : ViewModel() {
    private val actionMutex = Mutex()
    private val mutableState = MutableStateFlow(WalletUiState(access = walletAccess.state.value))
    val state: StateFlow<WalletUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            walletAccess.state.collect { access -> update { it.copy(access = access) } }
        }
    }

    fun dispatch(action: WalletAction) {
        viewModelScope.launch { actionMutex.withLock { reduce(action) } }
    }

    override fun onCleared() {
        dispose()
    }

    fun dispose() {
        viewModelScope.cancel()
        mutableState.value = mutableState.value.copy(
            words = blankWords(mutableState.value.words.size),
            passphrase = ""
        )
    }

    private suspend fun reduce(action: WalletAction) {
        when (action) {
            is WalletAction.RecoveryPhraseChanged -> update {
                it.copy(words = wordsFrom(action.value), message = null)
            }

            is WalletAction.WordChanged -> update {
                it.copy(words = it.words.withTyped(action.index, action.value), message = null)
            }

            is WalletAction.WordCompleted -> update { state ->
                val completion = state.words.getOrNull(action.index)
                    ?.let(RecoveryPhraseWords::completion)
                    ?: return@update state
                state.copy(
                    words = state.words.toMutableList().also {
                        it[action.index] = completion
                    }
                )
            }

            is WalletAction.WordCountChanged -> update {
                if (action.count !in RecoveryPhraseWords.VALID_COUNTS) return@update it
                it.copy(words = it.words.resized(action.count), message = null)
            }

            is WalletAction.PassphraseChanged -> update {
                it.copy(passphrase = action.value, message = null)
            }

            WalletAction.Import -> importWallet()

            WalletAction.Retry -> walletAccess.retryConnection()

            WalletAction.RequestRemoval -> update { it.copy(confirmRemoval = true) }

            WalletAction.CancelRemoval -> update { it.copy(confirmRemoval = false) }

            WalletAction.ConfirmRemoval -> {
                update {
                    it.copy(
                        confirmRemoval = false,
                        words = blankWords(it.words.size),
                        passphrase = ""
                    )
                }
                removeWallet()
            }
        }
    }

    private suspend fun importWallet() {
        val phrase = state.value.recoveryPhrase
        val passphrase = state.value.passphrase
        update { it.copy(words = blankWords(it.words.size), passphrase = "", message = null) }
        val message = when (walletAccess.importWallet(phrase, passphrase)) {
            ImportWalletResult.IMPORTED -> null
            ImportWalletResult.ALREADY_CONFIGURED -> WalletMessage.ALREADY_CONFIGURED
            ImportWalletResult.INVALID_MNEMONIC -> WalletMessage.INVALID_MNEMONIC
            ImportWalletResult.CONNECTION_FAILED -> WalletMessage.CONNECTION_FAILED
            ImportWalletResult.CREDENTIAL_STORE_FAILED -> WalletMessage.CREDENTIAL_STORE_FAILED
        }
        update { it.copy(message = message) }
    }

    private suspend fun removeWallet() {
        val message = when (walletAccess.removeWallet()) {
            RemoveWalletResult.REMOVED -> null
            RemoveWalletResult.RESET_REQUIRED -> WalletMessage.RESET_REQUIRED
        }
        update { it.copy(message = message) }
    }

    private inline fun update(transform: (WalletUiState) -> WalletUiState) {
        mutableState.value = transform(mutableState.value)
    }
}

/** A whole phrase: its own length when valid, else as many fields as offered lengths need. */
private fun wordsFrom(text: String): List<String> {
    val words = RecoveryPhraseWords.split(text)
    val count = if (words.size in RecoveryPhraseWords.VALID_COUNTS) {
        words.size
    } else {
        RecoveryPhraseWords.OFFERED_COUNTS.firstOrNull { it >= words.size }
            ?: RecoveryPhraseWords.OFFERED_COUNTS.last()
    }
    return words.take(count).resized(count)
}

private fun List<String>.withTyped(index: Int, value: String): List<String> {
    if (index !in indices) return this
    val typed = RecoveryPhraseWords.split(value)
    // A whole phrase pasted into the first field replaces the phrase, whatever its length.
    if (index == 0 && typed.size > 1 && typed.size in RecoveryPhraseWords.VALID_COUNTS) {
        return wordsFrom(value)
    }
    val result = toMutableList()
    if (typed.size <= 1) {
        result[index] = typed.firstOrNull().orEmpty()
        return result
    }
    typed.forEachIndexed { offset, word ->
        if (index + offset <
            size
        ) {
            result[index + offset] = word
        }
    }
    return result
}

private fun List<String>.resized(count: Int): List<String> =
    if (count <= size) take(count) else this + List(count - size) { "" }
