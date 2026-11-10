package xyz.lilsus.raylsuite.feature.themesettings

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
import xyz.lilsus.raylsuite.core.model.ThemePreference
import xyz.lilsus.raylsuite.core.raylapi.TelemetrySetting
import xyz.lilsus.raylsuite.core.telemetry.Telemetry

data class ThemeSettingsUiState(val selected: ThemePreference = ThemePreference.System)

class ThemeSettingsViewModel(
    private val preferences: ThemePreferences,
    dispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableUiState = MutableStateFlow(ThemeSettingsUiState())

    val uiState: StateFlow<ThemeSettingsUiState> = mutableUiState.asStateFlow()

    init {
        scope.launch {
            preferences.preference.collectLatest { preference ->
                mutableUiState.value = ThemeSettingsUiState(selected = preference)
            }
        }
    }

    fun selectTheme(preference: ThemePreference) {
        if (preference == mutableUiState.value.selected) return

        mutableUiState.value = ThemeSettingsUiState(selected = preference)
        Telemetry.settingChanged(TelemetrySetting.Theme)
        scope.launch {
            preferences.set(preference)
        }
    }

    fun clear() {
        scope.cancel()
    }
}
