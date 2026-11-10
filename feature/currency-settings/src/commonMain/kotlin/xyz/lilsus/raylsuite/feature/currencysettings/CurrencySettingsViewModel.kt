package xyz.lilsus.raylsuite.feature.currencysettings

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.raylapi.TelemetrySetting
import xyz.lilsus.raylsuite.core.telemetry.Telemetry

data class CurrencySettingsUiState(
    val selectedCode: String = CurrencyCatalog.DEFAULT_CODE,
    val searchQuery: String = ""
)

class CurrencySettingsViewModel(
    private val preferences: CurrencyPreferences,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableUiState = MutableStateFlow(CurrencySettingsUiState())

    val uiState: StateFlow<CurrencySettingsUiState> = mutableUiState.asStateFlow()

    init {
        scope.launch {
            preferences.code.collectLatest { code ->
                mutableUiState.value = mutableUiState.value.copy(selectedCode = code)
            }
        }
    }

    fun updateSearch(query: String) {
        mutableUiState.value = mutableUiState.value.copy(searchQuery = query)
    }

    fun selectCurrency(code: String) {
        val normalisedCode = CurrencyCatalog.infoFor(code).code
        mutableUiState.value = mutableUiState.value.copy(selectedCode = normalisedCode)
        Telemetry.settingChanged(TelemetrySetting.Currency)
        scope.launch {
            preferences.setCode(normalisedCode)
        }
    }

    fun clear() {
        scope.cancel()
    }
}
