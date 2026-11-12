package xyz.lilsus.rayl

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.blink.BlinkExperience
import xyz.lilsus.rayl.providers.nwc.NwcExperience
import xyz.lilsus.rayl.providers.rayl.RaylExperience
import xyz.lilsus.rayl.providers.rayl.RaylPasskeys
import xyz.lilsus.rayl.providers.rayl.raylRecoveryPhrase
import xyz.lilsus.rayl.providers.rayl.startRaylPocketEarly
import xyz.lilsus.rayl.providers.spark.SparkEnvironment
import xyz.lilsus.rayl.providers.spark.SparkExperience
import xyz.lilsus.rayl.shared.R
import xyz.lilsus.raylsuite.core.model.ThemePreference
import xyz.lilsus.raylsuite.core.settings.createAppSettings
import xyz.lilsus.raylsuite.core.settings.rememberAppSettings
import xyz.lilsus.raylsuite.core.telemetry.TelemetryMode
import xyz.lilsus.raylsuite.core.telemetry.installTelemetry
import xyz.lilsus.raylsuite.core.ui.platform.rememberRetainedInstance
import xyz.lilsus.raylsuite.core.ui.theme.RaylSuiteTheme
import xyz.lilsus.raylsuite.feature.themesettings.DefaultThemePreferences
import xyz.lilsus.raylsuite.feature.walletmanagement.LocalWalletCardIcons
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletCardProvider

@Composable
fun App(
    sparkEnvironment: SparkEnvironment,
    raylPasskeys: RaylPasskeys,
    /** A debug build, which offers development tools such as erasing Rayl's wallet. */
    debugTools: Boolean,
    breezApiKey: String? = null
) {
    val settings = rememberAppSettings(RAYL_PREFERENCES)
    val context = LocalContext.current.applicationContext
    val registry = rememberRetainedInstance(
        key = "rayl-cards",
        factory = { RaylCards(settings) },
        onDispose = {}
    )
    val cardsHost = rememberRetainedInstance(
        key = "rayl-cards-host",
        factory = {
            RaylWalletCardsHost(
                registry = registry,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
                provider = { wallet -> wallet.cardProvider(context) },
                clearCard = { clearSavedCard(context, it) },
                pocketRecoveryPhrase = {
                    raylRecoveryPhrase(context, sparkEnvironment.toRaylEnvironment())
                }
            )
        },
        onDispose = {}
    )
    val paying by registry.paying.collectAsStateWithLifecycle()
    val notice by registry.notice.collectAsStateWithLifecycle()
    LaunchedEffect(notice) {
        val shown = notice as? RaylCardNotice.AlreadyAdded ?: return@LaunchedEffect
        val existing = shown.cardId?.let { id -> registry.cards.value.firstOrNull { it.id == id } }
        val message = if (existing == null) {
            context.getString(R.string.card_already_pocket)
        } else {
            context.getString(R.string.card_already_added, existing.name)
        }
        // Not a question: Rayl already pays with that wallet.
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        registry.dismissNotice()
    }
    val session by registry.session.collectAsStateWithLifecycle()
    val preparation by registry.preparation.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(registry, paying) {
        registry.prepare({ clearSavedCard(context, it) })
    }
    val appOnboarding = rememberRetainedInstance(
        key = "rayl-app-onboarding-state",
        factory = { RaylAppOnboarding(settings) },
        onDispose = {}
    )
    val appOnboardingStep by appOnboarding.step.collectAsStateWithLifecycle()
    val theme = remember(paying, session) { DefaultThemePreferences(settings) }
    val preference by theme.preference.collectAsStateWithLifecycle(
        initialValue = ThemePreference.System
    )
    RaylSuiteTheme(themePreference = preference) {
        if (preparation != WalletPreparation.Ready) {
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (preparation == WalletPreparation.Failed) {
                    Text(stringResource(R.string.wallet_cleanup_failed))
                    TextButton(onClick = {
                        scope.launch { registry.prepare({ clearSavedCard(context, it) }) }
                    }) {
                        Text(stringResource(R.string.wallet_cleanup_retry))
                    }
                } else {
                    CircularProgressIndicator()
                }
            }
            return@RaylSuiteTheme
        }
        CompositionLocalProvider(LocalWalletCardIcons provides walletCardIcons) {
            key(paying, session) {
                when (val card = paying) {
                    null -> if (appOnboardingStep != AppOnboardingStep.Complete) {
                        AppOnboarding(appOnboarding, settings)
                    } else {
                        RaylExperience(
                            configuration = RAYL_POCKET,
                            environment = sparkEnvironment.toRaylEnvironment(),
                            breezApiKey = breezApiKey,
                            passkeys = raylPasskeys,
                            debugTools = debugTools,
                            cards = cardsHost,
                            onErased = registry::restartPocket,
                            onConnectionChanged = {}
                        )
                    }

                    else -> CardExperience(
                        card = card,
                        registry = registry,
                        cardsHost = cardsHost,
                        sparkEnvironment = sparkEnvironment,
                        breezApiKey = breezApiKey
                    )
                }
            }
        }
    }
}

/** The provider experience of the paying [card]. */
@Composable
private fun CardExperience(
    card: RaylCard,
    registry: RaylCards,
    cardsHost: RaylWalletCardsHost,
    sparkEnvironment: SparkEnvironment,
    breezApiKey: String?
) {
    val onConnectionChanged = { connected: Boolean ->
        if (connected) registry.connected(card)
    }
    when (card.wallet) {
        ExternalWallet.Blink -> BlinkExperience(
            configuration = card.blinkConfiguration(),
            startMode = registry.startMode,
            cards = cardsHost,
            onRemoved = { registry.removed(card) },
            onConnectionChanged = onConnectionChanged
        )

        ExternalWallet.Nwc -> NwcExperience(
            configuration = card.nwcConfiguration(),
            startMode = registry.startMode,
            cards = cardsHost,
            onRemoved = { registry.removed(card) },
            onConnectionChanged = onConnectionChanged
        )

        ExternalWallet.Spark -> SparkExperience(
            configuration = card.sparkConfiguration(),
            startMode = registry.startMode,
            environment = sparkEnvironment,
            breezApiKey = breezApiKey,
            cards = cardsHost,
            onRemoved = { registry.removed(card) },
            onChooseWallet = registry::cancelSetup,
            onConnectionChanged = onConnectionChanged
        )
    }
}

private fun ExternalWallet.cardProvider(context: Context): WalletCardProvider {
    val (title, description) = when (this) {
        ExternalWallet.Blink -> R.string.blink_title to R.string.blink_body
        ExternalWallet.Nwc -> R.string.nwc_title to R.string.nwc_body
        ExternalWallet.Spark -> R.string.spark_title to R.string.spark_body
    }
    return WalletCardProvider(id, context.getString(title), context.getString(description))
}

/** Each provider's own mark, by [ExternalWallet.id], where the shared screens show cards. */
private val walletCardIcons: Map<String, Int> = mapOf(
    ExternalWallet.Blink.id to R.drawable.wallet_blink,
    ExternalWallet.Nwc.id to R.drawable.wallet_nwc,
    ExternalWallet.Spark.id to R.drawable.wallet_spark
)

/**
 * Starts Rayl's anonymous usage data for this process; call it first in `Application.onCreate`.
 * Only installations that are not onboarded yet report onboarding steps.
 */
fun installRaylTelemetry(application: Application, mode: TelemetryMode, appVersion: String) {
    val settings = createAppSettings(application, RAYL_PREFERENCES)
    installTelemetry(
        application = application,
        settings = settings,
        mode = mode,
        appVersion = appVersion,
        newInstallation = { !appOnboarded(settings) }
    )
}

/** Starts Rayl's wallet before any UI when it is in use, so it connects while the app launches. */
fun startConfiguredWalletEarly(
    context: Context,
    sparkEnvironment: SparkEnvironment,
    breezApiKey: String? = null
) {
    if (usesRaylWallet(createAppSettings(context, RAYL_PREFERENCES))) {
        startRaylPocketEarly(context, sparkEnvironment.toRaylEnvironment(), breezApiKey)
    }
}
