package xyz.lilsus.rayl.providers.spark

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletAccess
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletAccessState
import xyz.lilsus.rayl.providers.spark.feature.walletconnection.NativeSparkWalletConnectionText
import xyz.lilsus.rayl.providers.spark.feature.walletconnection.WalletAction
import xyz.lilsus.rayl.providers.spark.feature.walletconnection.WalletMessage
import xyz.lilsus.rayl.providers.spark.feature.walletconnection.WalletViewModel
import xyz.lilsus.rayl.providers.spark.feature.walletconnection.nativeSparkWalletConnectionText
import xyz.lilsus.raylsuite.feature.walletmanagement.nativeWalletManagementText

data class SparkNativeWalletSettingsSnapshot(
    val removeTitle: String,
    val removeBusyMessage: String,
    val isWorking: Boolean,
    val errorMessage: String?
)

class SparkNativeWalletSettingsController internal constructor(
    walletAccess: WalletAccess,
    languageChanges: Flow<*>,
    private val isSubmitting: StateFlow<Boolean>,
    private val appName: String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val wallet = WalletViewModel(walletAccess)
    private val snapshot = MutableStateFlow<SparkNativeWalletSettingsSnapshot?>(null)

    init {
        scope.launch {
            combine(wallet.state, languageChanges, isSubmitting) { _, _, _ -> Unit }
                .collect { publishSnapshot() }
        }
    }

    fun observe(onChange: (SparkNativeWalletSettingsSnapshot) -> Unit): () -> Unit {
        val job = scope.launch { snapshot.filterNotNull().collect(onChange) }
        return { job.cancel() }
    }

    fun clear() {
        scope.cancel()
        wallet.dispose()
        snapshot.value = null
    }

    fun removeWallet() {
        if (!isSubmitting.value) wallet.dispatch(WalletAction.ConfirmRemoval)
    }

    private suspend fun publishSnapshot() {
        val state = wallet.state.value
        val management = nativeWalletManagementText()
        val connection = nativeSparkWalletConnectionText(appName)
        snapshot.value =
            SparkNativeWalletSettingsSnapshot(
                removeTitle = management.removeTitle,
                removeBusyMessage = management.removeBusyMessage,
                isWorking = state.access != WalletAccessState.Connected || isSubmitting.value,
                errorMessage = state.message?.message(connection)
            )
    }
}

private fun WalletMessage.message(text: NativeSparkWalletConnectionText): String = when (this) {
    WalletMessage.ALREADY_CONFIGURED -> text.errorAlreadyConfigured
    WalletMessage.INVALID_MNEMONIC -> text.errorInvalidMnemonic
    WalletMessage.CONNECTION_FAILED -> text.errorConnection
    WalletMessage.CREDENTIAL_STORE_FAILED -> text.errorStorage
    WalletMessage.RESET_REQUIRED -> text.errorReset
}
