package xyz.lilsus.rayl.providers.blink.feature.walletconnection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkApiError
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkApiException
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkConnectionError
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkConnectionException
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkWallet
import xyz.lilsus.rayl.providers.blink.ui.BlinkUiError

class AddBlinkWalletViewModel(
    private val blinkWallet: BlinkWallet,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var connectionJob: Job? = null

    private val mutableUiState = MutableStateFlow(AddBlinkWalletUiState())
    val uiState: StateFlow<AddBlinkWalletUiState> = mutableUiState.asStateFlow()

    private val mutableEvents =
        MutableSharedFlow<AddBlinkWalletEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<AddBlinkWalletEvent> = mutableEvents.asSharedFlow()

    fun updateApiKey(apiKey: String) {
        if (!scope.isActive || mutableUiState.value.isSaving) return
        mutableUiState.update { it.copy(apiKey = apiKey, error = null) }
    }

    fun submit() {
        if (!scope.isActive || connectionJob?.isActive == true) return
        val state = mutableUiState.value
        val apiKey = state.apiKey.trim()

        if (apiKey.isBlank()) {
            mutableUiState.update {
                it.copy(error = BlinkUiError.Connection(BlinkConnectionError.ApiKeyRequired))
            }
            return
        }

        connectionJob = scope.launch {
            mutableUiState.update { it.copy(isSaving = true, error = null) }

            try {
                blinkWallet.connect(apiKey = apiKey)

                coroutineContext.ensureActive()
                mutableUiState.value = AddBlinkWalletUiState()
                mutableEvents.emit(AddBlinkWalletEvent.Success)
            } catch (error: BlinkApiException) {
                coroutineContext.ensureActive()
                mutableUiState.update {
                    it.copy(
                        isSaving = false,
                        error = BlinkUiError.Api(error.error.withoutServerDetails())
                    )
                }
            } catch (error: BlinkConnectionException) {
                coroutineContext.ensureActive()
                mutableUiState.update {
                    it.copy(isSaving = false, error = BlinkUiError.Connection(error.error))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                coroutineContext.ensureActive()
                mutableUiState.update {
                    it.copy(
                        isSaving = false,
                        error = BlinkUiError.Unexpected(null)
                    )
                }
            }
        }
    }

    fun cancel() {
        reset()
        mutableEvents.tryEmit(AddBlinkWalletEvent.Cancelled)
    }

    fun reset() {
        connectionJob?.cancel()
        connectionJob = null
        mutableUiState.value = AddBlinkWalletUiState()
    }

    fun clear() {
        reset()
        scope.cancel()
    }
}

data class AddBlinkWalletUiState(
    val apiKey: String = "",
    val isSaving: Boolean = false,
    val error: BlinkUiError? = null
) {
    val canSubmit: Boolean
        get() = apiKey.isNotBlank() && !isSaving

    override fun toString(): String =
        "AddBlinkWalletUiState(apiKey=<redacted>, isSaving=$isSaving, error=$error)"
}

sealed interface AddBlinkWalletEvent {
    data object Success : AddBlinkWalletEvent

    data object Cancelled : AddBlinkWalletEvent
}

private fun BlinkApiError.withoutServerDetails(): BlinkApiError = when (this) {
    is BlinkApiError.Unexpected -> BlinkApiError.Unexpected()
    is BlinkApiError.PaymentRejected -> BlinkApiError.PaymentRejected()
    else -> this
}
