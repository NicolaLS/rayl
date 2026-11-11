package xyz.lilsus.raylsuite.feature.appshell

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import xyz.lilsus.raylsuite.feature.appshell.R

/**
 * The Android tab shell: a Material 3 navigation bar over the selected tab's content. iOS uses a
 * native `TabView` instead and renders one tab's content per view controller.
 */
@Composable
fun AppTabScaffold(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (AppTab) -> Unit
) {
    val tabContentState = rememberSaveableStateHolder()
    Scaffold(
        modifier = modifier.testTag(AppShellTestTags.SCAFFOLD),
        // This scaffold owns the status bar area, so it has to match the Hub's grouped background
        // there; otherwise a lighter strip sits above the Hub.
        containerColor = if (selectedTab == AppTab.Hub) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.background
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    val label = stringResource(tab.labelResource())
                    NavigationBarItem(
                        selected = tab == selectedTab,
                        onClick = { onTabSelected(tab) },
                        modifier = Modifier.testTag(AppShellTestTags.tab(tab)),
                        icon = {
                            Icon(imageVector = tab.icon, contentDescription = null)
                        },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
        ) {
            tabContentState.SaveableStateProvider(selectedTab.storedValue) {
                content(selectedTab)
            }
        }
    }
}

@StringRes
private fun AppTab.labelResource(): Int = when (this) {
    AppTab.Scan -> R.string.app_tab_scan
    AppTab.Hub -> R.string.app_tab_hub
    AppTab.Settings -> R.string.app_tab_settings
}

object AppShellTestTags {
    const val SCAFFOLD = "app_tab_scaffold"

    fun tab(tab: AppTab): String = "app_tab_${tab.storedValue}"
}
