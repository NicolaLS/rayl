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
import xyz.lilsus.raylsuite.feature.languagesettings.LanguageSettingsViewModel
import xyz.lilsus.raylsuite.feature.paymentsettings.PaymentPreferencesRepository
import xyz.lilsus.raylsuite.feature.paymentsettings.PaymentSettingsViewModel
import xyz.lilsus.raylsuite.feature.settings.R
import xyz.lilsus.raylsuite.feature.themesettings.ThemePreferences
import xyz.lilsus.raylsuite.feature.themesettings.ThemeSettingsViewModel

/**
 * The Settings tab: an overview that changes every setting in place, plus the currency list. It is
 * a tab root, so [onBack] is null unless a host wants an explicit way out of the whole flow.
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
    overviewTopContent: (@Composable () -> Unit)? = null
) {
    var destination by rememberSaveable { mutableStateOf(SettingsDestination.Overview) }

    fun navigateBack() {
        when (destination) {
            SettingsDestination.Overview -> onBack?.invoke()
            SettingsDestination.Currency -> destination = SettingsDestination.Overview
        }
    }

    NavigationEventHandler(
        state =
            rememberNavigationEventState(currentInfo = SettingsNavigationInfo(destination)),
        isForwardEnabled = false,
        isBackEnabled = destination != SettingsDestination.Overview || onBack != null,
        onBackCompleted = ::navigateBack
    )

    when (destination) {
        SettingsDestination.Overview -> SettingsOverview(
            themePreferences = themePreferences,
            languageRepository = languageRepository,
            bitcoinPriceProvider = bitcoinPriceProvider,
            currencyPreferences = currencyPreferences,
            paymentPreferences = paymentPreferences,
            legalLinks = legalLinks,
            onBack = onBack,
            onCurrency = { destination = SettingsDestination.Currency },
            modifier = modifier,
            overviewTopContent = overviewTopContent
        )

        SettingsDestination.Currency -> {
            val viewModel = remember(currencyPreferences) {
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
    }
}

@Composable
private fun SettingsOverview(
    themePreferences: ThemePreferences,
    languageRepository: LanguageRepository,
    bitcoinPriceProvider: BitcoinPriceProvider,
    currencyPreferences: CurrencyPreferences,
    paymentPreferences: PaymentPreferencesRepository,
    legalLinks: SettingsLegalLinks,
    onBack: (() -> Unit)?,
    onCurrency: () -> Unit,
    modifier: Modifier,
    overviewTopContent: (@Composable () -> Unit)?
) {
    val payments = remember(paymentPreferences, currencyPreferences, bitcoinPriceProvider) {
        PaymentSettingsViewModel(
            paymentPreferences = paymentPreferences,
            currencyPreferences = currencyPreferences,
            bitcoinPriceProvider = bitcoinPriceProvider
        )
    }
    ClearOnDispose(payments, payments::clear)
    val languages = remember(languageRepository) { LanguageSettingsViewModel(languageRepository) }
    ClearOnDispose(languages, languages::clear)
    val themes = remember(themePreferences) { ThemeSettingsViewModel(themePreferences) }
    ClearOnDispose(themes, themes::clear)

    val payment by payments.uiState.collectAsStateWithLifecycle()
    val language by languages.uiState.collectAsStateWithLifecycle()
    val currencyCode by currencyPreferences.code.collectAsStateWithLifecycle(
        CurrencyCatalog.DEFAULT_CODE
    )
    val languagePreference by languageRepository.preference.collectAsStateWithLifecycle()
    val themePreference by
        themePreferences.preference.collectAsStateWithLifecycle(ThemePreference.System)
    val usageDataEnabled by Telemetry.enabled.collectAsStateWithLifecycle()

    SettingsScreen(
        onBack = onBack,
        payment = payment,
        currencySubtitle = currencyCode,
        languages = LanguageCatalog.supported.map {
            SettingsChoice(it.code, languageName(it.code))
        },
        selectedLanguage = language.selectedCode,
        languageSubtitle = languageSubtitle(languagePreference),
        themes = listOf(
            SettingsChoice(THEME_SYSTEM, themeTitle(ThemePreference.System)),
            SettingsChoice(THEME_LIGHT, themeTitle(ThemePreference.Light)),
            SettingsChoice(THEME_DARK, themeTitle(ThemePreference.Dark))
        ),
        selectedTheme = when (themePreference) {
            ThemePreference.System -> THEME_SYSTEM
            ThemePreference.Light -> THEME_LIGHT
            ThemePreference.Dark -> THEME_DARK
        },
        usageDataEnabled = usageDataEnabled,
        actions = SettingsActions(
            onCurrency = onCurrency,
            onLanguageSelected = languages::selectOption,
            onThemeSelected = { id ->
                themes.selectTheme(
                    when (id) {
                        THEME_LIGHT -> ThemePreference.Light
                        THEME_DARK -> ThemePreference.Dark
                        else -> ThemePreference.System
                    }
                )
            },
            onConfirmationModeSelected = payments::selectConfirmationMode,
            onThresholdChanged = payments::updateConfirmationThreshold,
            onOfferToSaveNewTargetsChanged = payments::setOfferToSaveNewTargets,
            onVibrateOnScanChanged = payments::setVibrateOnScan,
            onVibrateOnPaymentChanged = payments::setVibrateOnPayment,
            onUsageDataChanged = Telemetry::setEnabled,
            onUsageDataAsked = Telemetry::usageDataAsked,
            onUsageDataKeptOn = Telemetry::usageDataKeptOn
        ),
        legalLinks = legalLinks,
        modifier = modifier,
        overviewTopContent = overviewTopContent
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
private fun themeTitle(preference: ThemePreference): String = when (preference) {
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

private const val THEME_SYSTEM = "system"
private const val THEME_LIGHT = "light"
private const val THEME_DARK = "dark"

private enum class SettingsDestination {
    Overview,
    Currency
}

private data class SettingsNavigationInfo(val destination: SettingsDestination) :
    NavigationEventInfo()
