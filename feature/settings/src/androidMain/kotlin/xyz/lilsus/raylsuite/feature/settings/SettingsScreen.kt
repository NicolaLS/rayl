package xyz.lilsus.raylsuite.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import xyz.lilsus.raylsuite.core.ui.components.AppFadingLazyColumn
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRow
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRowDefaults
import xyz.lilsus.raylsuite.core.ui.components.BackIconButton
import xyz.lilsus.raylsuite.core.ui.platform.appVersionName
import xyz.lilsus.raylsuite.core.ui.theme.RaylSuiteTheme
import xyz.lilsus.raylsuite.feature.settings.R

@Immutable
data class SettingsEntry(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector? = null,
    val testTag: String? = null,
    val onClick: () -> Unit
) {
    init {
        require(id.isNotBlank()) { "Settings entry ID must not be blank" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)?,
    onPayments: () -> Unit,
    onCurrency: () -> Unit,
    onLanguage: () -> Unit,
    onTheme: () -> Unit,
    legalLinks: SettingsLegalLinks,
    modifier: Modifier = Modifier,
    currencySubtitle: String? = null,
    languageSubtitle: String? = null,
    themeSubtitle: String? = null,
    overviewBottomContent: (@Composable () -> Unit)? = null,
    leadingEntries: List<SettingsEntry> = emptyList(),
    trailingEntries: List<SettingsEntry> = emptyList(),
    usageDataEnabled: Boolean = true,
    onUsageDataChanged: (Boolean) -> Unit = {},
    /** The question to turn usage data off was shown, and answered with "keep on". */
    onUsageDataAsked: () -> Unit = {},
    onUsageDataKeptOn: () -> Unit = {}
) {
    // Turning usage data off asks once, with the reason to keep it; turning it on does not.
    var confirmUsageDataOff by rememberSaveable { mutableStateOf(false) }
    if (confirmUsageDataOff) {
        val keepOn = {
            confirmUsageDataOff = false
            onUsageDataKeptOn()
        }
        AlertDialog(
            onDismissRequest = keepOn,
            title = { Text(stringResource(R.string.settings_usage_data_off_title)) },
            text = { Text(stringResource(R.string.settings_usage_data_off_body)) },
            confirmButton = {
                TextButton(onClick = keepOn) {
                    Text(stringResource(R.string.settings_usage_data_off_keep))
                }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.testTag(SettingsTestTags.USAGE_DATA_TURN_OFF),
                    onClick = {
                        confirmUsageDataOff = false
                        onUsageDataChanged(false)
                    }
                ) {
                    Text(stringResource(R.string.settings_usage_data_off_confirm))
                }
            }
        )
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val resolvedCurrencySubtitle =
        currencySubtitle ?: stringResource(R.string.settings_currency_subtitle)
    val resolvedLanguageSubtitle =
        languageSubtitle ?: stringResource(R.string.settings_language_subtitle)
    val resolvedThemeSubtitle =
        themeSubtitle ?: stringResource(R.string.settings_theme_subtitle)
    val sharedEntries =
        listOf(
            SettingsEntry(
                id = "payments",
                title = stringResource(R.string.settings_payments),
                subtitle = stringResource(R.string.settings_payments_subtitle),
                icon = Icons.Filled.Bolt,
                testTag = SettingsTestTags.PAYMENTS_ROW,
                onClick = onPayments
            ),
            SettingsEntry(
                id = "currency",
                title = stringResource(R.string.settings_currency),
                subtitle = resolvedCurrencySubtitle,
                icon = Icons.Filled.CurrencyExchange,
                testTag = SettingsTestTags.CURRENCY_ROW,
                onClick = onCurrency
            ),
            SettingsEntry(
                id = "language",
                title = stringResource(R.string.settings_language),
                subtitle = resolvedLanguageSubtitle,
                icon = Icons.Filled.Language,
                testTag = SettingsTestTags.LANGUAGE_ROW,
                onClick = onLanguage
            ),
            SettingsEntry(
                id = "theme",
                title = stringResource(R.string.settings_theme),
                subtitle = resolvedThemeSubtitle,
                icon = Icons.Filled.Contrast,
                testTag = SettingsTestTags.THEME_ROW,
                onClick = onTheme
            )
        )
    val entries = leadingEntries + sharedEntries + trailingEntries
    require(entries.distinctBy(SettingsEntry::id).size == entries.size) {
        "Settings entry IDs must be unique"
    }

    Scaffold(
        modifier = modifier.testTag(SettingsTestTags.SCREEN),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    onBack?.let { back -> BackIconButton(onClick = back) }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        AppFadingLazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentPadding = AppSettingsRowDefaults.ScreenPadding,
            verticalArrangement = Arrangement.spacedBy(AppSettingsRowDefaults.RowSpacing)
        ) {
            items(entries, key = { entry -> "entry:${entry.id}" }) { entry ->
                SettingsListItem(entry)
            }
            item(key = "usage-data") {
                AppSettingsRow(
                    title = stringResource(R.string.settings_usage_data),
                    modifier = Modifier.toggleable(
                        value = usageDataEnabled,
                        role = Role.Switch,
                        onValueChange = { enabled ->
                            if (enabled) {
                                onUsageDataChanged(true)
                            } else {
                                confirmUsageDataOff = true
                                onUsageDataAsked()
                            }
                        }
                    ),
                    subtitle = stringResource(R.string.settings_usage_data_subtitle),
                    subtitleMaxLines = 2,
                    icon = Icons.Filled.Insights,
                    testTag = SettingsTestTags.USAGE_DATA_ROW
                ) {
                    Switch(checked = usageDataEnabled, onCheckedChange = null)
                }
            }
            overviewBottomContent?.let { content ->
                item(key = "slot:overview-bottom-content") {
                    content()
                }
            }
            item {
                SettingsFooter(legalLinks)
            }
        }
    }
}

@Composable
private fun SettingsListItem(entry: SettingsEntry) {
    AppSettingsRow(
        title = entry.title,
        onClick = entry.onClick,
        subtitle = entry.subtitle,
        icon = entry.icon,
        testTag = entry.testTag
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(AppSettingsRowDefaults.ChevronSize)
        )
    }
}

@Composable
private fun SettingsFooter(legalLinks: SettingsLegalLinks) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text =
                stringResource(
                    R.string.settings_footer_version,
                    appVersionName()
                ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            legalLinks.privacyPolicyUrl?.let { privacyPolicyUrl ->
                Text(
                    text = stringResource(R.string.settings_footer_privacy),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { uriHandler.openUri(privacyPolicyUrl) }
                )
            }
            legalLinks.termsUrl?.let { termsUrl ->
                Text(
                    text = stringResource(R.string.settings_footer_terms),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { uriHandler.openUri(termsUrl) }
                )
            }
            Text(
                text = stringResource(R.string.settings_footer_repo),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.clickable {
                        uriHandler.openUri(legalLinks.sourceCodeUrl)
                    }
            )
        }
    }
}

object SettingsTestTags {
    const val SCREEN = "settings_screen"
    const val PAYMENTS_ROW = "settings_payments_row"
    const val CURRENCY_ROW = "settings_currency_row"
    const val LANGUAGE_ROW = "settings_language_row"
    const val THEME_ROW = "settings_theme_row"
    const val USAGE_DATA_ROW = "settings_usage_data_row"
    const val USAGE_DATA_TURN_OFF = "settings_usage_data_turn_off"
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    RaylSuiteTheme {
        SettingsScreen(
            onBack = {},
            onPayments = {},
            onCurrency = {},
            onLanguage = {},
            onTheme = {},
            legalLinks =
                SettingsLegalLinks(
                    privacyPolicyUrl = "https://example.com/privacy",
                    termsUrl = "https://example.com/terms",
                    sourceCodeUrl = "https://example.com/source"
                ),
            currencySubtitle = "SAT",
            languageSubtitle = "English",
            themeSubtitle = "System default"
        )
    }
}
