package xyz.lilsus.rayl.providers.nwc.feature.walletconnection

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
import xyz.lilsus.rayl.providers.nwc.integration.nwc.NwcConnectionError
import xyz.lilsus.rayl.providers.nwc.integration.nwc.NwcConnectionException
import xyz.lilsus.rayl.providers.nwc.integration.nwc.NwcWallet
import xyz.lilsus.rayl.providers.nwc.integration.nwc.NwcWalletConnection
import xyz.lilsus.rayl.providers.nwc.integration.nwc.NwcWalletDiscovery

class ConnectNwcWalletViewModel(
    private val nwcWallet: NwcWallet,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableUiState = MutableStateFlow(ConnectNwcWalletUiState())
    private val mutableEvents =
        MutableSharedFlow<ConnectNwcWalletEvent>(extraBufferCapacity = 4)
    private var discoveryJob: Job? = null
    private var saveJob: Job? = null

    val uiState: StateFlow<ConnectNwcWalletUiState> = mutableUiState.asStateFlow()
    val events: SharedFlow<ConnectNwcWalletEvent> = mutableEvents.asSharedFlow()

    fun load(uri: String) {
        val normalized = uri.trim()
        if (!scope.isActive || mutableUiState.value.isSaving) return
        if (normalized.isEmpty()) {
            reset()
            mutableUiState.value =
                ConnectNwcWalletUiState(
                    uri = normalized,
                    error = NwcConnectionError.InvalidUri
                )
            return
        }
        if (
            mutableUiState.value.uri == normalized &&
            mutableUiState.value.discovery != null
        ) {
            return
        }

        discoveryJob?.cancel()
        discoveryJob =
            scope.launch {
                mutableUiState.update {
                    it.copy(
                        uri = normalized,
                        alias = if (it.uri == normalized) it.alias else "",
                        isDiscoveryLoading = true,
                        discovery = null,
                        error = null
                    )
                }
                try {
                    val discovery = nwcWallet.discover(normalized)
                    coroutineContext.ensureActive()
                    mutableUiState.update { current ->
                        current.copy(
                            discovery = discovery,
                            alias =
                                current.alias.ifBlank {
                                    discovery.aliasSuggestion.orEmpty().toSingleLine()
                                },
                            isDiscoveryLoading = false
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: NwcConnectionException) {
                    coroutineContext.ensureActive()
                    mutableUiState.update {
                        it.copy(
                            isDiscoveryLoading = false,
                            error = error.error.withoutServerDetails()
                        )
                    }
                } catch (error: Exception) {
                    coroutineContext.ensureActive()
                    mutableUiState.update {
                        it.copy(
                            isDiscoveryLoading = false,
                            error = NwcConnectionError.ConnectionFailed()
                        )
                    }
                }
            }
    }

    fun retryDiscovery() {
        mutableUiState.value.uri.takeIf(String::isNotBlank)?.let(::load)
    }

    fun updateAlias(alias: String) {
        if (!scope.isActive || mutableUiState.value.isSaving) return
        mutableUiState.update {
            it.copy(
                alias = alias.toSingleLine(),
                error = null
            )
        }
    }

    fun confirm() {
        if (!scope.isActive) return
        val state = mutableUiState.value
        val discovery = state.discovery ?: return
        if (state.isSaving) return
        if (!discovery.supportsRequiredMethods) {
            mutableUiState.update {
                it.copy(error = NwcConnectionError.RequiredMethodsMissing)
            }
            return
        }

        saveJob = scope.launch {
            mutableUiState.update { it.copy(isSaving = true, error = null) }
            try {
                val connection =
                    nwcWallet.connect(
                        discovery = discovery,
                        alias = state.alias
                    )
                coroutineContext.ensureActive()
                mutableUiState.value = ConnectNwcWalletUiState()
                mutableEvents.emit(ConnectNwcWalletEvent.Success(connection))
            } catch (error: CancellationException) {
                throw error
            } catch (error: NwcConnectionException) {
                coroutineContext.ensureActive()
                mutableUiState.update {
                    it.copy(isSaving = false, error = error.error.withoutServerDetails())
                }
            } catch (error: Exception) {
                coroutineContext.ensureActive()
                mutableUiState.update {
                    it.copy(
                        isSaving = false,
                        error = NwcConnectionError.ConnectionFailed()
                    )
                }
            }
        }
    }

    fun cancel() {
        if (mutableUiState.value.isSaving) return
        reset()
        mutableEvents.tryEmit(ConnectNwcWalletEvent.Cancelled)
    }

    fun reset() {
        discoveryJob?.cancel()
        discoveryJob = null
        saveJob?.cancel()
        saveJob = null
        mutableUiState.value = ConnectNwcWalletUiState()
    }

    fun clear() {
        reset()
        scope.cancel()
    }
}

data class ConnectNwcWalletUiState(
    val uri: String = "",
    val isDiscoveryLoading: Boolean = false,
    val discovery: NwcWalletDiscovery? = null,
    val alias: String = "",
    val isSaving: Boolean = false,
    val error: NwcConnectionError? = null
) {
    /** The connection URI carries the wallet secret. */
    override fun toString(): String =
        "ConnectNwcWalletUiState(uri=<redacted>, isDiscoveryLoading=$isDiscoveryLoading, " +
            "discovery=$discovery, alias=$alias, isSaving=$isSaving, error=$error)"
}

sealed interface ConnectNwcWalletEvent {
    data class Success(val connection: NwcWalletConnection) : ConnectNwcWalletEvent

    data object Cancelled : ConnectNwcWalletEvent
}

private fun String.toSingleLine(): String = replace(Regex("[\\r\\n]+"), " ")

private fun NwcConnectionError.withoutServerDetails(): NwcConnectionError = when (this) {
    is NwcConnectionError.ConnectionFailed -> NwcConnectionError.ConnectionFailed()
    else -> this
}
