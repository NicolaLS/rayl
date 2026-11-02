package xyz.lilsus.rayl.providers.nwc.feature.walletconnection

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.nwc.integration.nwc.NwcConnectionError
import xyz.lilsus.rayl.providers.nwc.integration.nwc.isValidNwcConnectionUri

class AddNwcWalletViewModel(dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var submissionJob: Job? = null
    private val mutableUiState = MutableStateFlow(AddNwcWalletUiState())
    private val mutableEvents =
        MutableSharedFlow<AddNwcWalletEvent>(extraBufferCapacity = 4)

    val uiState: StateFlow<AddNwcWalletUiState> = mutableUiState.asStateFlow()
    val events: SharedFlow<AddNwcWalletEvent> = mutableEvents.asSharedFlow()

    fun updateUri(uri: String) {
        if (!scope.isActive) return
        mutableUiState.update {
            it.copy(
                uri = uri,
                error = null,
                isUriValid = isValid(uri.trim())
            )
        }
    }

    fun prefillUriIfValid(candidate: String?) {
        if (!scope.isActive) return
        val normalized = candidate?.trim()?.takeIf(::isValid) ?: return
        mutableUiState.value =
            AddNwcWalletUiState(
                uri = normalized,
                isUriValid = true
            )
    }

    fun submit() {
        if (!scope.isActive) return
        val normalized = mutableUiState.value.uri.trim()
        if (!isValid(normalized)) {
            mutableUiState.update {
                it.copy(
                    error = NwcConnectionError.InvalidUri,
                    isUriValid = false
                )
            }
            return
        }

        reset()
        submissionJob = scope.launch {
            mutableEvents.emit(AddNwcWalletEvent.Confirm(normalized))
        }
    }

    fun handleScannedValue(value: String) {
        if (!scope.isActive) return
        val normalized = value.trim()
        if (normalized.isEmpty()) return
        val isValid = isValid(normalized)
        mutableUiState.value =
            AddNwcWalletUiState(
                uri = normalized,
                isUriValid = isValid
            )
        if (isValid) {
            submit()
        }
    }

    fun reset() {
        submissionJob?.cancel()
        submissionJob = null
        mutableUiState.value = AddNwcWalletUiState()
    }

    fun clear() {
        reset()
        scope.cancel()
    }

    private fun isValid(uri: String): Boolean = uri.isNotEmpty() && isValidNwcConnectionUri(uri)
}

data class AddNwcWalletUiState(
    val uri: String = "",
    val error: NwcConnectionError? = null,
    val isUriValid: Boolean = false
) {
    /** The connection URI carries the wallet secret. */
    override fun toString(): String =
        "AddNwcWalletUiState(uri=<redacted>, error=$error, isUriValid=$isUriValid)"
}

sealed interface AddNwcWalletEvent {
    data class Confirm(val uri: String) : AddNwcWalletEvent {
        override fun toString(): String = "Confirm(uri=<redacted>)"
    }
}
