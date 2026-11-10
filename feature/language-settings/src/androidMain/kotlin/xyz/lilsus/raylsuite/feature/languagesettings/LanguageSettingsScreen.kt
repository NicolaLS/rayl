package xyz.lilsus.raylsuite.feature.languagesettings

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import xyz.lilsus.raylsuite.core.model.LanguageCatalog
import xyz.lilsus.raylsuite.core.ui.components.AppListDefaults
import xyz.lilsus.raylsuite.core.ui.components.AppListScaffold
import xyz.lilsus.raylsuite.core.ui.components.AppSelectableListRow
import xyz.lilsus.raylsuite.core.ui.components.BackIconButton
import xyz.lilsus.raylsuite.core.ui.format.rememberAppLocale
import xyz.lilsus.raylsuite.core.ui.theme.RaylSuiteTheme
import xyz.lilsus.raylsuite.feature.languagesettings.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSettingsScreen(
    state: LanguageSettingsUiState,
    onQueryChange: (String) -> Unit,
    onOptionSelected: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val localeTag = rememberAppLocale().languageTag
    val options =
        LanguageCatalog.supported.map { language ->
            LanguageOption(
                id = language.code,
                title = languageDisplayName(language.code)
            )
        }
    val filtered =
        options.filter { option ->
            option.title.contains(state.searchQuery, ignoreCase = true)
        }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = modifier,
        topBar = {
            key(localeTag) {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.settings_language)) },
                    navigationIcon = {
                        BackIconButton(onClick = onBack)
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        }
    ) { padding ->
        AppListScaffold(
            isEmpty = filtered.isEmpty(),
            emptyMessage = null,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .navigationBarsPadding()
                    .padding(AppListDefaults.ScreenPadding),
            showSearchBar = false,
            searchQuery = state.searchQuery,
            onSearchQueryChange = onQueryChange,
            searchPlaceholder = stringResource(R.string.search_placeholder)
        ) {
            items(filtered, key = LanguageOption::id) { option ->
                AppSelectableListRow(
                    title = option.title,
                    selected = state.selectedCode == option.id,
                    onClick = { onOptionSelected(option.id) }
                )
            }
        }
    }
}

private data class LanguageOption(val id: String, val title: String)

private fun languageDisplayName(code: String): String = when (code) {
    "de" -> "Deutsch"
    "es" -> "Español"
    else -> "English"
}

@Preview
@Composable
private fun LanguageSettingsScreenPreview() {
    RaylSuiteTheme {
        LanguageSettingsScreen(
            state =
                LanguageSettingsUiState(
                    selectedCode = "de",
                    deviceCode = "de"
                ),
            onQueryChange = {},
            onOptionSelected = {},
            onBack = {}
        )
    }
}
