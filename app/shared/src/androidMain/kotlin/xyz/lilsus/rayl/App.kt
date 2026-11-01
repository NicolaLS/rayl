package xyz.lilsus.rayl

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.blink.BlinkExperience
import xyz.lilsus.rayl.providers.nwc.NwcExperience
import xyz.lilsus.rayl.providers.rayl.RaylExperience
import xyz.lilsus.rayl.providers.rayl.RaylPasskeys
import xyz.lilsus.rayl.providers.rayl.RaylWalletOption
import xyz.lilsus.rayl.providers.rayl.startRaylPocketEarly
import xyz.lilsus.rayl.providers.spark.SparkEnvironment
import xyz.lilsus.rayl.providers.spark.SparkExperience
import xyz.lilsus.rayl.shared.R
import xyz.lilsus.raylsuite.core.model.ThemePreference
import xyz.lilsus.raylsuite.core.settings.createAppSettings
import xyz.lilsus.raylsuite.core.settings.rememberAppSettings
import xyz.lilsus.raylsuite.core.ui.platform.rememberRetainedInstance
import xyz.lilsus.raylsuite.core.ui.theme.RaylSuiteTheme
import xyz.lilsus.raylsuite.feature.themesettings.DefaultThemePreferences

@Composable
fun App(
    sparkEnvironment: SparkEnvironment,
    raylPasskeys: RaylPasskeys,
    /** A debug build, which offers development tools such as erasing Rayl's wallet. */
    debugTools: Boolean,
    breezApiKey: String? = null
) {
    val settings = rememberAppSettings(RAYL_PREFERENCES)
    val selection = rememberRetainedInstance(
        key = "rayl-selection",
        factory = { RaylSelection(settings) },
        onDispose = {}
    )
    val external by selection.external.collectAsStateWithLifecycle()
    val session by selection.session.collectAsStateWithLifecycle()
    val preparation by selection.preparation.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    LaunchedEffect(selection, external) {
        selection.prepare { clearSavedWallet(context, it) }
    }
    val appOnboarding = rememberRetainedInstance(
        key = "rayl-app-onboarding-state",
        factory = { RaylAppOnboarding(settings) },
        onDispose = {}
    )
    val appOnboardingStep by appOnboarding.step.collectAsStateWithLifecycle()
    val theme = remember(external, session) { DefaultThemePreferences(settings) }
    val preference by theme.preference.collectAsStateWithLifecycle(
        initialValue = ThemePreference.System
    )
    val backToRayl = { selection.clear() }
    val useWallet = { id: String ->
        val wallet = ExternalWallet.fromId(id)
        if (wallet != null && selection.external.value == null) selection.choose(wallet)
    }
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
                        scope.launch { selection.prepare { clearSavedWallet(context, it) } }
                    }) {
                        Text(stringResource(R.string.wallet_cleanup_retry))
                    }
                } else {
                    CircularProgressIndicator()
                }
            }
            return@RaylSuiteTheme
        }
        key(external, session) {
            when (external) {
                null -> if (appOnboardingStep != AppOnboardingStep.Complete) {
                    AppOnboarding(appOnboarding, settings)
                } else {
                    RaylExperience(
                        configuration = RAYL_POCKET,
                        environment = sparkEnvironment.toRaylEnvironment(),
                        breezApiKey = breezApiKey,
                        passkeys = raylPasskeys,
                        debugTools = debugTools,
                        otherWallets = externalWalletOptions(),
                        onUseWallet = useWallet,
                        onErased = selection::restartRaylWallet,
                        onConnectionChanged = {}
                    )
                }

                ExternalWallet.Blink -> BlinkExperience(
                    configuration = RAYL_BLINK,
                    startMode = selection.startMode,
                    onRemoved = backToRayl,
                    onConnectionChanged = {
                        if (it && selection.external.value == ExternalWallet.Blink) {
                            selection.connected(ExternalWallet.Blink)
                        }
                    }
                )

                ExternalWallet.Nwc -> NwcExperience(
                    configuration = RAYL_NWC,
                    startMode = selection.startMode,
                    onRemoved = backToRayl,
                    onConnectionChanged = {
                        if (it && selection.external.value == ExternalWallet.Nwc) {
                            selection.connected(ExternalWallet.Nwc)
                        }
                    }
                )

                ExternalWallet.Spark -> SparkExperience(
                    configuration = RAYL_SPARK,
                    startMode = selection.startMode,
                    environment = sparkEnvironment,
                    breezApiKey = breezApiKey,
                    onRemoved = backToRayl,
                    onChooseWallet = backToRayl,
                    onConnectionChanged = {
                        if (it && selection.external.value == ExternalWallet.Spark) {
                            selection.connected(ExternalWallet.Spark)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun externalWalletOptions(): List<RaylWalletOption> = ExternalWallet.entries.map { wallet ->
    val (title, description) = when (wallet) {
        ExternalWallet.Blink -> R.string.blink_title to R.string.blink_body
        ExternalWallet.Nwc -> R.string.nwc_title to R.string.nwc_body
        ExternalWallet.Spark -> R.string.spark_title to R.string.spark_body
    }
    RaylWalletOption(wallet.id, stringResource(title), stringResource(description))
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
