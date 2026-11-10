package xyz.lilsus.raylsuite.feature.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.core.model.CurrencyCatalog
import xyz.lilsus.raylsuite.core.model.LanguageCatalog
import xyz.lilsus.raylsuite.core.model.PaymentConfirmationMode
import xyz.lilsus.raylsuite.core.model.PaymentPreferences
import xyz.lilsus.raylsuite.core.model.ThemePreference
import xyz.lilsus.raylsuite.core.payment.BitcoinPriceProvider
import xyz.lilsus.raylsuite.core.telemetry.Telemetry
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString
import xyz.lilsus.raylsuite.feature.currencysettings.CurrencyPreferences
import xyz.lilsus.raylsuite.feature.currencysettings.CurrencySettingsViewModel
import xyz.lilsus.raylsuite.feature.currencysettings.nativeCurrencyStrings
import xyz.lilsus.raylsuite.feature.languagesettings.LanguageRepository
import xyz.lilsus.raylsuite.feature.languagesettings.LanguageSettingsViewModel
import xyz.lilsus.raylsuite.feature.languagesettings.nativeLanguageSearchPlaceholder
import xyz.lilsus.raylsuite.feature.paymentsettings.PaymentPreferencesRepository
import xyz.lilsus.raylsuite.feature.paymentsettings.PaymentSettingsViewModel
import xyz.lilsus.raylsuite.feature.paymentsettings.nativePaymentSettingsStrings
import xyz.lilsus.raylsuite.feature.themesettings.ThemePreferences
import xyz.lilsus.raylsuite.feature.themesettings.ThemeSettingsViewModel

data class NativeSettingsOption(val id: String, val title: String)

data class NativeSettingsAmount(val minor: Long, val currencyCode: String, val fractionDigits: Int)

data class NativeSettingsLink(val title: String, val url: String)

data class NativePaymentSettingsSnapshot(
    val confirmationMode: String,
    val thresholdSats: Long,
    val thresholdSteps: List<Long>,
    val thresholdEquivalent: NativeSettingsAmount?,
    val offerToSaveNewTargets: Boolean,
    val vibrateOnScan: Boolean,
    val vibrateOnPayment: Boolean
)

data class NativeSettingsText(
    val settingsTitle: String,
    val paymentsTitle: String,
    val paymentsSubtitle: String,
    val currencyTitle: String,
    val currencySubtitle: String,
    val currencySearch: String,
    val languageTitle: String,
    val languageSearch: String,
    val languageSystemSettingsHint: String,
    val themeTitle: String,
    val paymentConfirmTitle: String,
    val paymentAlways: String,
    val paymentAbove: String,
    val paymentHubTitle: String,
    val paymentOfferSaveTargets: String,
    val paymentHapticsTitle: String,
    val paymentHapticsScan: String,
    val paymentHapticsPayment: String,
    val usageDataTitle: String,
    val usageDataSubtitle: String,
    val usageDataOffTitle: String,
    val usageDataOffBody: String,
    val usageDataOffConfirm: String,
    val usageDataOffKeep: String
)

data class NativeSettingsSnapshot(
    val text: NativeSettingsText,
    val themeOptions: List<NativeSettingsOption>,
    val selectedThemeId: String,
    val languageOptions: List<NativeSettingsOption>,
    val selectedLanguageId: String,
    val languageManagedBySystem: Boolean,
    val currencyOptions: List<NativeSettingsOption>,
    val selectedCurrencyId: String,
    val payment: NativePaymentSettingsSnapshot,
    /** Whether anonymous usage data is shared; see `TelemetryCatalog.kt`. */
    val usageDataEnabled: Boolean,
    val versionText: String,
    val legalLinks: List<NativeSettingsLink>
)

/**
 * Presentation-only boundary for native Settings renderers. It owns no provider behavior and
 * reports only explicit settings intents.
 */
class NativeSettingsController(
    themePreferences: ThemePreferences,
    languageRepository: LanguageRepository,
    currencyPreferences: CurrencyPreferences,
    paymentPreferences: PaymentPreferencesRepository,
    bitcoinPriceProvider: BitcoinPriceProvider,
    private val legalLinks: SettingsLegalLinks,
    private val appVersionName: String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val theme = ThemeSettingsViewModel(themePreferences)
    private val language = LanguageSettingsViewModel(languageRepository)
    private val currency = CurrencySettingsViewModel(currencyPreferences, Dispatchers.Main)
    private val payment =
        PaymentSettingsViewModel(
            paymentPreferences = paymentPreferences,
            currencyPreferences = currencyPreferences,
            bitcoinPriceProvider = bitcoinPriceProvider
        )

    fun observe(onChange: (NativeSettingsSnapshot) -> Unit): () -> Unit {
        val job =
            scope.launch {
                combine(
                    theme.uiState,
                    language.uiState,
                    currency.uiState,
                    payment.uiState,
                    Telemetry.enabled
                ) { themeState, languageState, currencyState, paymentState, usageDataEnabled ->
                    val resources = loadResources(legalLinks, appVersionName)
                    NativeSettingsSnapshot(
                        text = resources.text,
                        themeOptions = resources.themeOptions,
                        selectedThemeId = themeState.selected.name.lowercase(),
                        languageOptions = resources.languageOptions,
                        selectedLanguageId = languageState.selectedCode,
                        languageManagedBySystem = !languageState.canSelectInApp,
                        currencyOptions = resources.currencyOptions,
                        selectedCurrencyId = currencyState.selectedCode,
                        payment =
                            NativePaymentSettingsSnapshot(
                                confirmationMode =
                                    paymentState.confirmationMode.name.lowercase(),
                                thresholdSats = paymentState.thresholdSats,
                                thresholdSteps = PaymentPreferences.THRESHOLD_STEPS,
                                thresholdEquivalent =
                                    paymentState.thresholdCurrencyEquivalent?.let { amount ->
                                        val info = CurrencyCatalog.infoFor(amount.currency)
                                        NativeSettingsAmount(
                                            minor = amount.minor,
                                            currencyCode = info.code,
                                            fractionDigits = info.fractionDigits
                                        )
                                    },
                                offerToSaveNewTargets = paymentState.offerToSaveNewTargets,
                                vibrateOnScan = paymentState.vibrateOnScan,
                                vibrateOnPayment = paymentState.vibrateOnPayment
                            ),
                        usageDataEnabled = usageDataEnabled,
                        versionText = resources.versionText,
                        legalLinks = resources.legalLinks
                    )
                }.collect(onChange)
            }
        return { job.cancel() }
    }

    fun selectTheme(id: String) {
        val preference =
            when (id.lowercase()) {
                "light" -> ThemePreference.Light
                "dark" -> ThemePreference.Dark
                else -> ThemePreference.System
            }
        theme.selectTheme(preference)
    }

    fun selectLanguage(id: String) {
        language.selectOption(id)
    }

    fun selectCurrency(id: String) {
        currency.selectCurrency(id)
    }

    fun selectConfirmationMode(id: String) {
        val mode =
            if (id.equals("always", ignoreCase = true)) {
                PaymentConfirmationMode.Always
            } else {
                PaymentConfirmationMode.Above
            }
        payment.selectConfirmationMode(mode)
    }

    fun selectThresholdStep(index: Int) {
        val threshold = PaymentPreferences.THRESHOLD_STEPS.getOrNull(index) ?: return
        payment.updateConfirmationThreshold(threshold)
    }

    fun setOfferToSaveNewTargets(enabled: Boolean) {
        payment.setOfferToSaveNewTargets(enabled)
    }

    fun setVibrateOnScan(enabled: Boolean) {
        payment.setVibrateOnScan(enabled)
    }

    fun setVibrateOnPayment(enabled: Boolean) {
        payment.setVibrateOnPayment(enabled)
    }

    /** Turning it off also discards what has not been sent yet. */
    fun setUsageDataEnabled(enabled: Boolean) {
        Telemetry.setEnabled(enabled)
    }

    /** The question to turn usage data off was shown. */
    fun usageDataAsked() {
        Telemetry.usageDataAsked()
    }

    /** The user answered that question with "keep on". */
    fun usageDataKeptOn() {
        Telemetry.usageDataKeptOn()
    }

    fun clear() {
        theme.clear()
        language.clear()
        currency.clear()
        payment.clear()
        scope.cancel()
    }
}

private data class NativeSettingsResources(
    val text: NativeSettingsText,
    val themeOptions: List<NativeSettingsOption>,
    val languageOptions: List<NativeSettingsOption>,
    val currencyOptions: List<NativeSettingsOption>,
    val versionText: String,
    val legalLinks: List<NativeSettingsLink>
)

private suspend fun loadResources(
    legalLinks: SettingsLegalLinks,
    appVersionName: String
): NativeSettingsResources {
    val currencyStrings = nativeCurrencyStrings()
    val paymentStrings = nativePaymentSettingsStrings()
    val themeOptions =
        listOf(
            NativeSettingsOption(
                id = "system",
                title = nativeString(
                    NativeStringResource(table = "Settings", key = "settings_theme_system")
                )
            ),
            NativeSettingsOption(
                id = "light",
                title = nativeString(
                    NativeStringResource(table = "Settings", key = "settings_theme_light")
                )
            ),
            NativeSettingsOption(
                id = "dark",
                title = nativeString(
                    NativeStringResource(table = "Settings", key = "settings_theme_dark")
                )
            )
        )
    val languageOptions =
        listOf(
            NativeSettingsOption(
                id = LanguageCatalog.supported[0].code,
                title = nativeString(
                    NativeStringResource(table = "Settings", key = "settings_language_english")
                )
            ),
            NativeSettingsOption(
                id = LanguageCatalog.supported[1].code,
                title = nativeString(
                    NativeStringResource(table = "Settings", key = "settings_language_german")
                )
            ),
            NativeSettingsOption(
                id = LanguageCatalog.supported[2].code,
                title = nativeString(
                    NativeStringResource(table = "Settings", key = "settings_language_spanish")
                )
            )
        )
    val currencyOptions =
        CurrencyCatalog.supported.map { info ->
            NativeSettingsOption(
                id = info.code,
                title = currencyStrings.getValue(info.code)
            )
        }
    val nativeLegalLinks =
        buildList {
            legalLinks.privacyPolicyUrl?.let { url ->
                add(
                    NativeSettingsLink(
                        title = nativeString(
                            NativeStringResource(
                                table = "Settings",
                                key = "settings_footer_privacy"
                            )
                        ),
                        url = url
                    )
                )
            }
            legalLinks.termsUrl?.let { url ->
                add(
                    NativeSettingsLink(
                        title = nativeString(
                            NativeStringResource(table = "Settings", key = "settings_footer_terms")
                        ),
                        url = url
                    )
                )
            }
            add(
                NativeSettingsLink(
                    title = nativeString(
                        NativeStringResource(table = "Settings", key = "settings_footer_repo")
                    ),
                    url = legalLinks.sourceCodeUrl
                )
            )
        }
    return NativeSettingsResources(
        text =
            NativeSettingsText(
                settingsTitle = nativeString(
                    NativeStringResource(table = "Settings", key = "settings_title")
                ),
                paymentsTitle = nativeString(
                    NativeStringResource(table = "Settings", key = "settings_payments")
                ),
                paymentsSubtitle = nativeString(
                    NativeStringResource(table = "Settings", key = "settings_payments_subtitle")
                ),
                currencyTitle = nativeString(
                    NativeStringResource(table = "Settings", key = "settings_currency")
                ),
                currencySubtitle = nativeString(
                    NativeStringResource(table = "Settings", key = "settings_currency_subtitle")
                ),
                currencySearch = currencyStrings.getValue("search"),
                languageTitle = nativeString(
                    NativeStringResource(table = "Settings", key = "settings_language")
                ),
                languageSearch = nativeLanguageSearchPlaceholder(),
                languageSystemSettingsHint =
                    nativeString(
                        NativeStringResource(
                            table = "Settings",
                            key = "settings_language_ios_settings"
                        )
                    ),
                themeTitle = nativeString(
                    NativeStringResource(table = "Settings", key = "settings_theme")
                ),
                paymentConfirmTitle = paymentStrings.getValue("confirmTitle"),
                paymentAlways = paymentStrings.getValue("always"),
                paymentAbove = paymentStrings.getValue("above"),
                paymentHubTitle = paymentStrings.getValue("hubTitle"),
                paymentOfferSaveTargets = paymentStrings.getValue("offerSaveTargets"),
                paymentHapticsTitle = paymentStrings.getValue("hapticsTitle"),
                paymentHapticsScan = paymentStrings.getValue("hapticsScan"),
                paymentHapticsPayment = paymentStrings.getValue("hapticsPayment"),
                usageDataTitle = nativeString(
                    NativeStringResource(table = "Settings", key = "settings_usage_data")
                ),
                usageDataSubtitle = nativeString(
                    NativeStringResource(table = "Settings", key = "settings_usage_data_subtitle")
                ),
                usageDataOffTitle = nativeString(
                    NativeStringResource(table = "Settings", key = "settings_usage_data_off_title")
                ),
                usageDataOffBody = nativeString(
                    NativeStringResource(table = "Settings", key = "settings_usage_data_off_body")
                ),
                usageDataOffConfirm = nativeString(
                    NativeStringResource(
                        table = "Settings",
                        key = "settings_usage_data_off_confirm"
                    )
                ),
                usageDataOffKeep = nativeString(
                    NativeStringResource(table = "Settings", key = "settings_usage_data_off_keep")
                )
            ),
        themeOptions = themeOptions,
        languageOptions = languageOptions,
        currencyOptions = currencyOptions,
        versionText = nativeString(
            NativeStringResource(table = "Settings", key = "settings_footer_version"),
            appVersionName
        ),
        legalLinks = nativeLegalLinks
    )
}
