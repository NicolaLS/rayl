package xyz.lilsus.rayl.providers.blink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.blink.feature.walletsettings.BlinkWalletSettingsViewModel
import xyz.lilsus.rayl.providers.blink.feature.walletsettings.NativeBlinkWalletSettingsText
import xyz.lilsus.rayl.providers.blink.feature.walletsettings.nativeBlinkWalletSettingsText
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkWallet
import xyz.lilsus.rayl.providers.blink.integration.blink.BlinkWalletCurrency
import xyz.lilsus.raylsuite.feature.languagesettings.LanguageRepository
import xyz.lilsus.raylsuite.feature.walletmanagement.nativeWalletManagementText

data class BlinkNativeWalletSettingsSnapshot(
    val fundingWalletTitle: String,
    val selectedFundingWalletTitle: String,
    val fundingWalletPickerTitle: String,
    val isLoadingFundingWallets: Boolean,
    val fundingWalletOptions: List<BlinkNativeFundingWalletOption>,
    val fundingWalletErrorMessage: String?,
    val fundingWalletUnavailableMessage: String?,
    val fundingWalletLoadingTitle: String,
    val fundingWalletCloseTitle: String,
    val canRemove: Boolean,
    val removeTitle: String,
    val removeBusyMessage: String
)

data class BlinkNativeFundingWalletOption(val id: String, val title: String, val selected: Boolean)

/** Blink-owned presentation boundary for the optional actions in its native Settings tab. */
class BlinkNativeSettingsController internal constructor(
    blinkWallet: BlinkWallet,
    private val languageRepository: LanguageRepository,
    private val appName: String,
    private val isSubmitting: StateFlow<Boolean>,
    private val onRemoveWallet: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val viewModel = BlinkWalletSettingsViewModel(blinkWallet)

    fun observe(onChange: (BlinkNativeWalletSettingsSnapshot) -> Unit): () -> Unit {
        val job =
            scope.launch {
                combine(
                    viewModel.uiState,
                    languageRepository.preference,
                    isSubmitting
                ) {
                        state,
                        _,
                        _
                    ->
                    state
                }
                    .collect { state ->
                        val text = nativeBlinkWalletSettingsText(state, appName)
                        val management = nativeWalletManagementText()
                        onChange(
                            BlinkNativeWalletSettingsSnapshot(
                                fundingWalletTitle = text.fundingWalletTitle,
                                selectedFundingWalletTitle =
                                    state.selectedWallet?.currency?.let { currency ->
                                        currency.title(text)
                                    } ?: text.chooseFundingWalletTitle,
                                fundingWalletPickerTitle = text.fundingWalletPickerTitle,
                                isLoadingFundingWallets = state.isLoading,
                                fundingWalletOptions =
                                    state.wallets.map { wallet ->
                                        BlinkNativeFundingWalletOption(
                                            id = wallet.id,
                                            title = wallet.currency.title(text),
                                            selected = state.selectedWallet?.id == wallet.id
                                        )
                                    },
                                fundingWalletErrorMessage = text.errorMessage,
                                fundingWalletUnavailableMessage =
                                    text.unavailableMessage.takeIf {
                                        state.selectionUnavailable
                                    },
                                fundingWalletLoadingTitle = text.loadingTitle,
                                fundingWalletCloseTitle = text.closeTitle,
                                canRemove = !isSubmitting.value,
                                removeTitle = management.removeTitle,
                                removeBusyMessage = management.removeBusyMessage
                            )
                        )
                    }
            }
        return { job.cancel() }
    }

    fun loadFundingWallets() {
        viewModel.loadFundingWallets()
    }

    fun selectFundingWallet(id: String) {
        viewModel.selectFundingWallet(id)
    }

    fun removeWallet() {
        onRemoveWallet()
    }

    fun clear() {
        viewModel.clear()
        scope.cancel()
    }
}

private fun BlinkWalletCurrency.title(text: NativeBlinkWalletSettingsText): String = when (this) {
    BlinkWalletCurrency.BTC -> text.bitcoinTitle
    BlinkWalletCurrency.USD -> text.stablesatsTitle
}
