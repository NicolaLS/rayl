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
import xyz.lilsus.rayl.providers.spark.application.wallet.RemoveWalletResult
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletAccess
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletAccessState

data class WalletUiState(
    val access: WalletAccessState = WalletAccessState.Loading,
    val recoveryPhrase: String = "",
    /** The optional BIP-39 passphrase, kept exactly as typed. */
    val passphrase: String = "",
    val message: WalletMessage? = null,
    val confirmRemoval: Boolean = false
)

enum class WalletMessage {
    ALREADY_CONFIGURED,
    INVALID_MNEMONIC,
    CONNECTION_FAILED,
    CREDENTIAL_STORE_FAILED,
    RESET_REQUIRED
}

sealed interface WalletAction {
    data class RecoveryPhraseChanged(val value: String) : WalletAction
    data class PassphraseChanged(val value: String) : WalletAction
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
        mutableState.value = mutableState.value.copy(recoveryPhrase = "", passphrase = "")
    }

    private suspend fun reduce(action: WalletAction) {
        when (action) {
            is WalletAction.RecoveryPhraseChanged -> update {
                it.copy(recoveryPhrase = action.value, message = null)
            }

            is WalletAction.PassphraseChanged -> update {
                it.copy(passphrase = action.value, message = null)
            }

            WalletAction.Import -> importWallet()

            WalletAction.Retry -> walletAccess.retryConnection()

            WalletAction.RequestRemoval -> update { it.copy(confirmRemoval = true) }

            WalletAction.CancelRemoval -> update { it.copy(confirmRemoval = false) }

            WalletAction.ConfirmRemoval -> {
                update { it.copy(confirmRemoval = false, recoveryPhrase = "", passphrase = "") }
                removeWallet()
            }
        }
    }

    private suspend fun importWallet() {
        val phrase = state.value.recoveryPhrase
        val passphrase = state.value.passphrase
        update { it.copy(recoveryPhrase = "", passphrase = "", message = null) }
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
