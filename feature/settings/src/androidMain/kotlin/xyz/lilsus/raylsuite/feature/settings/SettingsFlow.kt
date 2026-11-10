package xyz.lilsus.raylsuite.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.LanguageCatalog
import xyz.lilsus.raylsuite.core.model.LanguagePreference
import xyz.lilsus.raylsuite.core.model.ThemePreference
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider
import xyz.lilsus.raylsuite.core.telemetry.Telemetry
import xyz.lilsus.raylsuite.feature.currencysettings.CurrencyPreferences
import xyz.lilsus.raylsuite.feature.currencysettings.CurrencySettingsScreen
import xyz.lilsus.raylsuite.feature.currencysettings.CurrencySettingsViewModel
import xyz.lilsus.raylsuite.feature.languagesettings.LanguageRepository
import xyz.lilsus.raylsuite.feature.languagesettings.LanguageSettingsScreen
import xyz.lilsus.raylsuite.feature.languagesettings.LanguageSettingsViewModel
import xyz.lilsus.raylsuite.feature.paymentsettings.PaymentPreferencesRepository
import xyz.lilsus.raylsuite.feature.paymentsettings.PaymentSettingsScreen
import xyz.lilsus.raylsuite.feature.paymentsettings.PaymentSettingsViewModel
import xyz.lilsus.raylsuite.feature.settings.R
import xyz.lilsus.raylsuite.feature.themesettings.ThemePreferences
import xyz.lilsus.raylsuite.feature.themesettings.ThemeSettingsScreen
import xyz.lilsus.raylsuite.feature.themesettings.ThemeSettingsViewModel

/**
 * The Settings tab: an overview plus its detail screens. It is a tab root, so [onBack] is null
 * unless a host wants an explicit way out of the whole flow.
 */
@Composable
fun SettingsFlow(
    themePreferences: ThemePreferences,
    languageRepository: LanguageRepository,
    bitcoinPriceProvider: BitcoinPriceProvider,
    currencyPreferences: CurrencyPreferences,
    paymentPreferences: PaymentPreferencesRepository,
    legalLinks: SettingsLegalLinks,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    overviewBottomContent: (@Composable () -> Unit)? = null,
    leadingEntries: List<SettingsEntry> = emptyList(),
    trailingEntries: List<SettingsEntry> = emptyList()
) {
    var destination by rememberSaveable { mutableStateOf(SettingsDestination.Overview) }

    val paymentSettingsViewModel =
        remember(paymentPreferences, currencyPreferences, bitcoinPriceProvider) {
            PaymentSettingsViewModel(
                paymentPreferences = paymentPreferences,
                currencyPreferences = currencyPreferences,
                bitcoinPriceProvider = bitcoinPriceProvider
            )
        }
    val paymentSettingsState by paymentSettingsViewModel.uiState.collectAsStateWithLifecycle()

    fun navigateBack() {
        when (destination) {
            SettingsDestination.Overview -> onBack?.invoke()
            else -> destination = SettingsDestination.Overview
        }
    }

    NavigationEventHandler(
        state =
            rememberNavigationEventState(currentInfo = SettingsNavigationInfo(destination)),
        isForwardEnabled = false,
        isBackEnabled = destination != SettingsDestination.Overview || onBack != null,
        onBackCompleted = ::navigateBack
    )

    DisposableEffect(paymentSettingsViewModel) {
        onDispose(paymentSettingsViewModel::clear)
    }

    when (destination) {
        SettingsDestination.Overview -> {
            SettingsOverview(
                currencyPreferences = currencyPreferences,
                languageRepository = languageRepository,
                themePreferences = themePreferences,
                legalLinks = legalLinks,
                onBack = onBack,
                onPayments = { destination = SettingsDestination.Payments },
                onCurrency = { destination = SettingsDestination.Currency },
                onLanguage = { destination = SettingsDestination.Language },
                onTheme = { destination = SettingsDestination.Theme },
                modifier = modifier,
                overviewBottomContent = overviewBottomContent,
                leadingEntries = leadingEntries,
                trailingEntries = trailingEntries
            )
        }

        SettingsDestination.Currency -> {
            val viewModel =
                remember(currencyPreferences) {
                    CurrencySettingsViewModel(currencyPreferences)
                }
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            ClearOnDispose(viewModel, viewModel::clear)
            CurrencySettingsScreen(
                state = state,
                onQueryChange = viewModel::updateSearch,
                onCurrencySelected = viewModel::selectCurrency,
                onBack = ::navigateBack,
                modifier = modifier
            )
        }

        SettingsDestination.Language -> {
            val viewModel =
                remember(languageRepository) {
                    LanguageSettingsViewModel(languageRepository)
                }
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            ClearOnDispose(viewModel, viewModel::clear)
            LanguageSettingsScreen(
                state = state,
                onQueryChange = viewModel::updateSearch,
                onOptionSelected = viewModel::selectOption,
                onBack = ::navigateBack,
                modifier = modifier
            )
        }

        SettingsDestination.Theme -> {
            val viewModel =
                remember(themePreferences) {
                    ThemeSettingsViewModel(themePreferences)
                }
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            ClearOnDispose(viewModel, viewModel::clear)
            ThemeSettingsScreen(
                state = state,
                onThemeSelected = viewModel::selectTheme,
                onBack = ::navigateBack,
                modifier = modifier
            )
        }

        SettingsDestination.Payments -> {
            PaymentSettingsScreen(
                state = paymentSettingsState,
                onBack = ::navigateBack,
                onModeSelected = paymentSettingsViewModel::selectConfirmationMode,
                onThresholdChanged =
                    paymentSettingsViewModel::updateConfirmationThreshold,
                onOfferToSaveNewTargetsChanged =
                    paymentSettingsViewModel::setOfferToSaveNewTargets,
                onVibrateOnScanChanged = paymentSettingsViewModel::setVibrateOnScan,
                onVibrateOnPaymentChanged =
                    paymentSettingsViewModel::setVibrateOnPayment,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun SettingsOverview(
    currencyPreferences: CurrencyPreferences,
    languageRepository: LanguageRepository,
    themePreferences: ThemePreferences,
    legalLinks: SettingsLegalLinks,
    onBack: (() -> Unit)?,
    onPayments: () -> Unit,
    onCurrency: () -> Unit,
    onLanguage: () -> Unit,
    onTheme: () -> Unit,
    modifier: Modifier,
    overviewBottomContent: (@Composable () -> Unit)?,
    leadingEntries: List<SettingsEntry>,
    trailingEntries: List<SettingsEntry>
) {
    val currencyCode by currencyPreferences.code.collectAsStateWithLifecycle(
        CurrencyCatalog.DEFAULT_CODE
    )
    val languagePreference by languageRepository.preference.collectAsStateWithLifecycle()
    val themePreference by
        themePreferences.preference.collectAsStateWithLifecycle(ThemePreference.System)
    val usageDataEnabled by Telemetry.enabled.collectAsStateWithLifecycle()

    SettingsScreen(
        onBack = onBack,
        onPayments = onPayments,
        onCurrency = onCurrency,
        onLanguage = onLanguage,
        onTheme = onTheme,
        legalLinks = legalLinks,
        modifier = modifier,
        currencySubtitle = currencyCode,
        languageSubtitle = languageSubtitle(languagePreference),
        themeSubtitle = themeSubtitle(themePreference),
        overviewBottomContent = overviewBottomContent,
        leadingEntries = leadingEntries,
        trailingEntries = trailingEntries,
        usageDataEnabled = usageDataEnabled,
        onUsageDataChanged = Telemetry::setEnabled
    )
}

@Composable
private fun ClearOnDispose(key: Any, clear: () -> Unit) {
    DisposableEffect(key) {
        onDispose(clear)
    }
}

@Composable
private fun languageSubtitle(preference: LanguagePreference): String {
    val resolvedName = languageName(preference.resolvedTag)
    return when (preference) {
        is LanguagePreference.System ->
            stringResource(
                R.string.settings_language_system_default,
                resolvedName
            )

        is LanguagePreference.Override -> languageName(preference.overrideTag)
    }
}

@Composable
private fun languageName(tag: String): String {
    val code =
        LanguageCatalog.infoForTag(tag)?.code
            ?: LanguageCatalog.infoForCode(tag.substringBefore('-'))?.code
            ?: LanguageCatalog.fallback.code
    return stringResource(languageNameResource(code))
}

@StringRes
private fun languageNameResource(code: String): Int = when (code) {
    "de" -> R.string.settings_language_german
    "es" -> R.string.settings_language_spanish
    else -> R.string.settings_language_english
}

@Composable
private fun themeSubtitle(preference: ThemePreference): String = when (preference) {
    ThemePreference.System ->
        stringResource(
            R.string.settings_theme_system_default,
            stringResource(
                if (isSystemInDarkTheme()) {
                    R.string.settings_theme_dark
                } else {
                    R.string.settings_theme_light
                }
            )
        )

    ThemePreference.Light -> stringResource(R.string.settings_theme_light)

    ThemePreference.Dark -> stringResource(R.string.settings_theme_dark)
}

private enum class SettingsDestination {
    Overview,
    Currency,
    Language,
    Theme,
    Payments
}

private data class SettingsNavigationInfo(val destination: SettingsDestination) :
    NavigationEventInfo()
