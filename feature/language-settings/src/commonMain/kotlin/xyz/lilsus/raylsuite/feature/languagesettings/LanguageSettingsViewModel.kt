package xyz.lilsus.raylsuite.feature.languagesettings

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
import xyz.lilsus.raylsuite.core.model.LanguageCatalog
import xyz.lilsus.raylsuite.core.model.LanguagePreference
import xyz.lilsus.raylsuite.core.raylapi.TelemetrySetting
import xyz.lilsus.raylsuite.core.telemetry.Telemetry

data class LanguageSettingsUiState(
    val searchQuery: String = "",
    val selectedCode: String = "",
    val deviceCode: String = "",
    val canSelectInApp: Boolean = true
)

class LanguageSettingsViewModel(
    private val repository: LanguageRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableUiState =
        MutableStateFlow(
            LanguageSettingsUiState(
                canSelectInApp = repository.management == LanguageManagement.InApp
            )
        )

    val uiState: StateFlow<LanguageSettingsUiState> = mutableUiState.asStateFlow()

    init {
        scope.launch {
            repository.refresh()
            repository.preference.collectLatest { preference ->
                val deviceCode = normaliseCode(preference.deviceTag)
                val selectedCode =
                    when (preference) {
                        is LanguagePreference.System -> deviceCode
                        is LanguagePreference.Override -> normaliseCode(preference.overrideTag)
                    }
                mutableUiState.value =
                    mutableUiState.value.copy(
                        selectedCode = selectedCode,
                        deviceCode = deviceCode,
                        canSelectInApp = repository.management == LanguageManagement.InApp
                    )
            }
        }
    }

    fun updateSearch(query: String) {
        mutableUiState.value = mutableUiState.value.copy(searchQuery = query)
    }

    fun selectOption(optionId: String) {
        if (repository.management != LanguageManagement.InApp) return

        Telemetry.settingChanged(TelemetrySetting.Language)
        scope.launch {
            if (optionId.equals(mutableUiState.value.deviceCode, ignoreCase = true)) {
                repository.clearOverride()
            } else {
                val tag = LanguageCatalog.infoForCode(optionId)?.tag ?: optionId
                repository.setLanguage(tag)
            }
        }
    }

    fun clear() {
        scope.cancel()
    }

    private fun normaliseCode(tag: String): String {
        LanguageCatalog.infoForTag(tag)?.let { return it.code }
        return LanguageCatalog.infoForCode(tag.substringBefore('-'))?.code
            ?: LanguageCatalog.fallback.code
    }
}
