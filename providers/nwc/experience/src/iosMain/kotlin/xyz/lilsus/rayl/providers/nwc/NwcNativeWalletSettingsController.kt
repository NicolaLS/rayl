package xyz.lilsus.rayl.providers.nwc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.nwc.feature.walletdetails.nativeNwcWalletDetailsText
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString
import xyz.lilsus.raylsuite.feature.walletmanagement.nativeWalletManagementText

data class NwcNativeWalletSettingsSnapshot(
    val removeTitle: String,
    val removeBusyMessage: String,
    val walletId: String?,
    val walletTitle: String?,
    val detailsTitle: String,
    val walletTypeLabel: String,
    val walletType: String,
    val connectionIdLabel: String,
    val walletFlowPresented: Boolean,
    val removalErrorMessage: String?,
    val isWorking: Boolean
)

class NwcNativeWalletSettingsController internal constructor(
    private val runtime: NwcRuntime,
    private val onboarding: NwcNativeOnboardingController
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val snapshot = MutableStateFlow<NwcNativeWalletSettingsSnapshot?>(null)
    private var isWorking = false
    private var walletFlowPrepared = false

    init {
        scope.launch {
            combine(
                runtime.nwcWallet.connection,
                runtime.settingsWalletFlow,
                runtime.languageRepository.preference,
                runtime.paymentCoordinator.isSubmitting,
                runtime.removalFailed
            ) { _, walletFlowPresented, _, _, _ -> walletFlowPresented }
                .collect { walletFlowPresented ->
                    if (walletFlowPresented && !walletFlowPrepared) {
                        walletFlowPrepared = true
                        onboarding.startSettingsWalletFlow()
                    } else if (!walletFlowPresented) {
                        walletFlowPrepared = false
                    }
                    publishSnapshot()
                }
        }
    }

    fun clear() {
        scope.coroutineContext[Job]?.cancel()
    }

    fun observe(onChange: (NwcNativeWalletSettingsSnapshot) -> Unit): () -> Unit {
        val job = scope.launch { snapshot.filterNotNull().collect(onChange) }
        return { job.cancel() }
    }

    fun requestWalletConnection() {
        walletFlowPrepared = true
        onboarding.startSettingsWalletFlow()
        runtime.requestSettingsWalletFlow()
    }

    fun finishWalletConnection() {
        walletFlowPrepared = false
        onboarding.finishSettingsWalletFlow()
    }

    fun removeWallet() {
        if (isWorking) return
        isWorking = true
        scope.launch {
            publishSnapshot()
            try {
                runtime.removeWallet()
            } finally {
                isWorking = false
                publishSnapshot()
            }
        }
    }

    private suspend fun publishSnapshot() {
        val management = nativeWalletManagementText()
        val details = nativeNwcWalletDetailsText()
        val connection = runtime.nwcWallet.connection.value

        snapshot.value =
            NwcNativeWalletSettingsSnapshot(
                removeTitle = management.removeTitle,
                removeBusyMessage = management.removeBusyMessage,
                walletId = connection?.walletPublicKey,
                walletTitle = connection?.alias?.takeIf(String::isNotBlank),
                detailsTitle = details.title,
                walletTypeLabel = details.typeLabel,
                walletType = details.walletType,
                connectionIdLabel = details.connectionIdLabel,
                walletFlowPresented = runtime.settingsWalletFlow.value,
                removalErrorMessage = if (runtime.removalFailed.value) {
                    nativeString(NativeStringResource("WalletManagement", "wallet_removal_failed"))
                } else {
                    null
                },
                isWorking = isWorking || runtime.paymentCoordinator.isSubmitting.value
            )
    }
}
