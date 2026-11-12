package xyz.lilsus.raylsuite.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency
import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode
import xyz.lilsus.raylsuite.core.model.PaymentPreferences
import xyz.lilsus.raylsuite.core.ui.components.AppFadingLazyColumn
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsPanel
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRow
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsRowDefaults
import xyz.lilsus.raylsuite.core.ui.components.AppSettingsSection
import xyz.lilsus.raylsuite.core.ui.components.BackIconButton
import xyz.lilsus.raylsuite.core.ui.format.rememberAmountFormatter
import xyz.lilsus.raylsuite.core.ui.platform.appVersionName
import xyz.lilsus.raylsuite.core.ui.theme.RaylSuiteTheme
import xyz.lilsus.raylsuite.feature.paymentsettings.PaymentSettingsUiState
import xyz.lilsus.raylsuite.feature.paymentsettings.R as PaymentR
import xyz.lilsus.raylsuite.feature.settings.R

/** One answer to a setting with a few fixed answers. */
@Immutable
data class SettingsChoice(val id: String, val title: String)

/**
 * What the overview changes. Every setting is changed right on the overview; only the currency,
 * one of many, opens a screen of its own.
 */
class SettingsActions(
    val onCurrency: () -> Unit,
    val onLanguageSelected: (String) -> Unit,
    val onThemeSelected: (String) -> Unit,
    val onConfirmationModeSelected: (PaymentConfirmationMode) -> Unit,
    val onThresholdChanged: (Long) -> Unit,
    val onOfferToSaveNewTargetsChanged: (Boolean) -> Unit,
    val onVibrateOnScanChanged: (Boolean) -> Unit,
    val onVibrateOnPaymentChanged: (Boolean) -> Unit,
    val onUsageDataChanged: (Boolean) -> Unit,
    /** The question to turn usage data off was shown, and answered with "keep on". */
    val onUsageDataAsked: () -> Unit = {},
    val onUsageDataKeptOn: () -> Unit = {}
)

/**
 * The Settings overview, in sections: the wallet block the host passes as [overviewTopContent],
 * payments, the app, and privacy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)?,
    payment: PaymentSettingsUiState,
    currencySubtitle: String,
    languages: List<SettingsChoice>,
    selectedLanguage: String,
    languageSubtitle: String,
    themes: List<SettingsChoice>,
    selectedTheme: String,
    usageDataEnabled: Boolean,
    actions: SettingsActions,
    legalLinks: SettingsLegalLinks,
    modifier: Modifier = Modifier,
    /** The wallet's rows, shown as the first section whatever they contain. */
    overviewTopContent: (@Composable () -> Unit)? = null
) {
    // Turning usage data off asks once, with the reason to keep it; turning it on does not.
    var confirmUsageDataOff by rememberSaveable { mutableStateOf(false) }
    if (confirmUsageDataOff) {
        val keepOn = {
            confirmUsageDataOff = false
            actions.onUsageDataKeptOn()
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
                        actions.onUsageDataChanged(false)
                    }
                ) {
                    Text(stringResource(R.string.settings_usage_data_off_confirm))
                }
            }
        )
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

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
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = AppSettingsRowDefaults.ScreenPadding,
            verticalArrangement = Arrangement.spacedBy(AppSettingsRowDefaults.SectionSpacing)
        ) {
            overviewTopContent?.let { content ->
                item(key = "wallet") { AppSettingsSection { content() } }
            }
            item(key = "payments") {
                AppSettingsSection(title = stringResource(R.string.settings_payments)) {
                    ConfirmationPanel(payment, actions)
                    AppSettingsRow(
                        title = stringResource(R.string.settings_currency),
                        subtitle = currencySubtitle,
                        icon = Icons.Filled.CurrencyExchange,
                        testTag = SettingsTestTags.CURRENCY_ROW,
                        onClick = actions.onCurrency
                    ) { Chevron() }
                    SwitchRow(
                        title = stringResource(
                            PaymentR.string.settings_payments_offer_save_targets
                        ),
                        subtitle = stringResource(
                            PaymentR.string.settings_payments_offer_save_targets_subtitle
                        ),
                        icon = Icons.Filled.PersonAdd,
                        checked = payment.offerToSaveNewTargets,
                        onCheckedChange = actions.onOfferToSaveNewTargetsChanged,
                        testTag = SettingsTestTags.OFFER_TO_SAVE_TARGETS
                    )
                }
            }
            item(key = "app") {
                AppSettingsSection(title = stringResource(R.string.settings_section_app)) {
                    ChoiceRow(
                        title = stringResource(R.string.settings_language),
                        value = languageSubtitle,
                        icon = Icons.Filled.Language,
                        choices = languages,
                        selected = selectedLanguage,
                        onSelect = actions.onLanguageSelected,
                        testTag = SettingsTestTags.LANGUAGE_ROW
                    )
                    ChoiceRow(
                        title = stringResource(R.string.settings_theme),
                        value = themes.firstOrNull { it.id == selectedTheme }?.title.orEmpty(),
                        icon = Icons.Filled.Contrast,
                        choices = themes,
                        selected = selectedTheme,
                        onSelect = actions.onThemeSelected,
                        testTag = SettingsTestTags.THEME_ROW
                    )
                    SwitchRow(
                        title = stringResource(PaymentR.string.settings_payments_haptics_scan),
                        icon = Icons.Filled.QrCodeScanner,
                        checked = payment.vibrateOnScan,
                        onCheckedChange = actions.onVibrateOnScanChanged
                    )
                    SwitchRow(
                        title = stringResource(PaymentR.string.settings_payments_haptics_payment),
                        icon = Icons.Filled.Vibration,
                        checked = payment.vibrateOnPayment,
                        onCheckedChange = actions.onVibrateOnPaymentChanged
                    )
                }
            }
            item(key = "privacy") {
                AppSettingsSection(title = stringResource(R.string.settings_section_privacy)) {
                    SwitchRow(
                        title = stringResource(R.string.settings_usage_data),
                        subtitle = stringResource(R.string.settings_usage_data_subtitle),
                        icon = Icons.Filled.Insights,
                        checked = usageDataEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                actions.onUsageDataChanged(true)
                            } else {
                                confirmUsageDataOff = true
                                actions.onUsageDataAsked()
                            }
                        },
                        testTag = SettingsTestTags.USAGE_DATA_ROW
                    )
                }
            }
            item(key = "footer") { SettingsFooter(legalLinks) }
        }
    }
}

/** Always, or only from an amount on, chosen right where the rule is named. */
@Composable
private fun ConfirmationPanel(payment: PaymentSettingsUiState, actions: SettingsActions) {
    val formatter = rememberAmountFormatter()
    AppSettingsPanel(Modifier.testTag(SettingsTestTags.CONFIRMATION)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.VerifiedUser,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(AppSettingsRowDefaults.IconSize)
            )
            Text(
                text = stringResource(PaymentR.string.settings_payments_confirm_label),
                style = MaterialTheme.typography.titleMedium
            )
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            confirmationModes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = payment.confirmationMode == mode,
                    onClick = { actions.onConfirmationModeSelected(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, confirmationModes.size),
                    modifier = Modifier.testTag(
                        if (mode == PaymentConfirmationMode.Always) {
                            SettingsTestTags.MODE_ALWAYS
                        } else {
                            SettingsTestTags.MODE_ABOVE
                        }
                    ),
                    label = { Text(stringResource(label)) }
                )
            }
        }
        // Only the threshold needs spelling out; "Always" already reads off the selector.
        if (payment.confirmationMode == PaymentConfirmationMode.Above) {
            val threshold = formatter.format(
                DisplayAmount(payment.thresholdSats, DisplayCurrency.Satoshi)
            )
            val equivalent =
                payment.thresholdCurrencyEquivalent?.let { " (${formatter.format(it)})" }.orEmpty()
            Text(
                text = stringResource(
                    PaymentR.string.settings_payments_confirm_threshold,
                    threshold + equivalent
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val steps = PaymentPreferences.THRESHOLD_STEPS
            Slider(
                value = PaymentPreferences.thresholdToStepIndex(payment.thresholdSats).toFloat(),
                onValueChange = { index -> actions.onThresholdChanged(steps[index.toInt()]) },
                valueRange = 0f..steps.lastIndex.toFloat(),
                steps = steps.size - 2
            )
        }
    }
}

/** A setting with a few fixed answers: the answer under its name, the others one tap away. */
@Composable
private fun ChoiceRow(
    title: String,
    value: String,
    icon: ImageVector,
    choices: List<SettingsChoice>,
    selected: String,
    onSelect: (String) -> Unit,
    testTag: String
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AppSettingsRow(
            title = title,
            subtitle = value,
            icon = icon,
            testTag = testTag,
            onClick = { expanded = true }
        ) {
            Icon(
                imageVector = Icons.Filled.UnfoldMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(AppSettingsRowDefaults.ChevronSize)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.title) },
                    onClick = {
                        expanded = false
                        onSelect(choice.id)
                    },
                    trailingIcon = if (choice.id == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    testTag: String? = null
) {
    AppSettingsRow(
        title = title,
        subtitle = subtitle,
        subtitleMaxLines = 2,
        icon = icon,
        testTag = testTag,
        modifier = Modifier.toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = onCheckedChange
        )
    ) {
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Chevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(AppSettingsRowDefaults.ChevronSize)
    )
}

@Composable
private fun SettingsFooter(legalLinks: SettingsLegalLinks) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_footer_version, appVersionName()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            legalLinks.privacyPolicyUrl?.let { url ->
                FooterLink(stringResource(R.string.settings_footer_privacy)) {
                    uriHandler.openUri(url)
                }
            }
            legalLinks.termsUrl?.let { url ->
                FooterLink(stringResource(R.string.settings_footer_terms)) {
                    uriHandler.openUri(url)
                }
            }
            FooterLink(stringResource(R.string.settings_footer_repo)) {
                uriHandler.openUri(legalLinks.sourceCodeUrl)
            }
        }
    }
}

@Composable
private fun FooterLink(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onClick)
    )
}

private val confirmationModes =
    listOf(
        PaymentConfirmationMode.Always to PaymentR.string.settings_payments_option_always,
        PaymentConfirmationMode.Above to PaymentR.string.settings_payments_option_above
    )

object SettingsTestTags {
    const val SCREEN = "settings_screen"
    const val CONFIRMATION = "settings_confirmation"
    const val MODE_ALWAYS = "settings_confirmation_always"
    const val MODE_ABOVE = "settings_confirmation_above"
    const val CURRENCY_ROW = "settings_currency_row"
    const val OFFER_TO_SAVE_TARGETS = "settings_offer_to_save_targets"
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
            onBack = null,
            payment = PaymentSettingsUiState(),
            currencySubtitle = "USD",
            languages = listOf(SettingsChoice("en", "English"), SettingsChoice("de", "Deutsch")),
            selectedLanguage = "en",
            languageSubtitle = "Use device language (English)",
            themes = listOf(SettingsChoice("system", "System"), SettingsChoice("dark", "Dark")),
            selectedTheme = "system",
            usageDataEnabled = true,
            actions = SettingsActions(
                onCurrency = {},
                onLanguageSelected = {},
                onThemeSelected = {},
                onConfirmationModeSelected = {},
                onThresholdChanged = {},
                onOfferToSaveNewTargetsChanged = {},
                onVibrateOnScanChanged = {},
                onVibrateOnPaymentChanged = {},
                onUsageDataChanged = {}
            ),
            legalLinks = SettingsLegalLinks(
                privacyPolicyUrl = "https://example.com/privacy",
                termsUrl = "https://example.com/terms",
                sourceCodeUrl = "https://example.com/source"
            )
        )
    }
}
