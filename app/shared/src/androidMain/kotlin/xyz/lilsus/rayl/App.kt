package xyz.lilsus.rayl

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.blink.BlinkDeepLinks
import xyz.lilsus.rayl.providers.blink.BlinkExperience
import xyz.lilsus.rayl.providers.nwc.NwcDeepLinks
import xyz.lilsus.rayl.providers.nwc.NwcExperience
import xyz.lilsus.rayl.providers.rayl.RaylExperience
import xyz.lilsus.rayl.providers.rayl.RaylPaymentLinks
import xyz.lilsus.rayl.providers.rayl.RaylWalletOption
import xyz.lilsus.rayl.providers.rayl.RaylWalletRequests
import xyz.lilsus.rayl.providers.rayl.startRaylPocketEarly
import xyz.lilsus.rayl.providers.spark.SparkDeepLinks
import xyz.lilsus.rayl.providers.spark.SparkEnvironment
import xyz.lilsus.rayl.providers.spark.SparkExperience
import xyz.lilsus.rayl.shared.R
import xyz.lilsus.raylsuite.core.model.ThemePreference
import xyz.lilsus.raylsuite.core.settings.createAppSettings
import xyz.lilsus.raylsuite.core.settings.rememberAppSettings
import xyz.lilsus.raylsuite.core.ui.platform.rememberRetainedInstance
import xyz.lilsus.raylsuite.core.ui.theme.RaylSuiteTheme
import xyz.lilsus.raylsuite.feature.onboarding.WelcomeScreen
import xyz.lilsus.raylsuite.feature.themesettings.DefaultThemePreferences

@Composable
fun App(sparkEnvironment: SparkEnvironment, breezApiKey: String? = null) {
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
    val welcomeCompleted by selection.welcomeCompleted.collectAsStateWithLifecycle()
    // Whether the external wallet in use is connected; Rayl's wallet queues links itself.
    var connected by remember { mutableStateOf<Boolean?>(null) }
    // A connection link that waits for Rayl's wallet to close before its setup starts.
    var pendingNwcLink by remember { mutableStateOf<String?>(null) }
    val theme = remember(external, session) { DefaultThemePreferences(settings) }
    val preference by theme.preference.collectAsStateWithLifecycle(
        initialValue = ThemePreference.System
    )
    var message by remember { mutableStateOf<Int?>(null) }
    val backToRayl = {
        RaylDeepLinks.clear()
        BlinkDeepLinks.clear()
        NwcDeepLinks.clear()
        RaylPaymentLinks.clear()
        SparkDeepLinks.clear()
        message = null
        connected = null
        selection.clear()
    }
    val useWallet = { id: String ->
        val wallet = ExternalWallet.fromId(id)
        if (wallet != null && selection.external.value == null) {
            connected = null
            RaylPaymentLinks.clear()
            selection.choose(wallet)
            if (wallet == ExternalWallet.Nwc) pendingNwcLink?.let(NwcDeepLinks::emit)
        }
        pendingNwcLink = null
    }
    LaunchedEffect(selection) {
        RaylWalletRequests.rejections.collect {
            pendingNwcLink = null
            message = R.string.wallet_switch_busy
        }
    }
    LaunchedEffect(selection, external) {
        RaylDeepLinks.events.collect { uri ->
            selection.preparation.first { it == WalletPreparation.Ready }
            val selected = selection.external.value
            if (uri.substringBefore(':').equals("nostr+walletconnect", true)) {
                if (selected == null) {
                    selection.completeWelcome()
                    pendingNwcLink = uri
                    RaylWalletRequests.request(ExternalWallet.Nwc.id)
                } else if (selected == ExternalWallet.Nwc &&
                    !snapshotFlow { connected }.filterNotNull().first()
                ) {
                    NwcDeepLinks.emit(uri)
                } else {
                    message = R.string.connection_exists
                }
            } else if (selected == null) {
                // Rayl's wallet queues links while it connects, or until the welcome is done.
                RaylPaymentLinks.emit(uri)
            } else if (!snapshotFlow { connected }.filterNotNull().first()) {
                // FIXME: Spark cold-start reports Loading -> false before async restore finishes,
                // which can reject a cold-start payment deep link as disconnected.
                message = R.string.connect_to_pay
            } else {
                when (selected) {
                    ExternalWallet.Blink -> BlinkDeepLinks.emit(uri)
                    ExternalWallet.Nwc -> NwcDeepLinks.emit(uri)
                    ExternalWallet.Spark -> SparkDeepLinks.emit(uri)
                }
            }
        }
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
                null -> if (!welcomeCompleted) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        WelcomeScreen(
                            title = stringResource(R.string.welcome_title),
                            subtitle = "",
                            description = stringResource(R.string.welcome_body),
                            actionTitle = stringResource(R.string.get_started),
                            onGetStarted = selection::completeWelcome
                        )
                    }
                } else {
                    RaylExperience(
                        configuration = RAYL_POCKET,
                        environment = sparkEnvironment.toRaylEnvironment(),
                        breezApiKey = breezApiKey,
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
                        if (selection.external.value == ExternalWallet.Blink) {
                            connected = it
                            if (it) selection.connected(ExternalWallet.Blink)
                        }
                    }
                )

                ExternalWallet.Nwc -> NwcExperience(
                    configuration = RAYL_NWC,
                    startMode = selection.startMode,
                    onRemoved = backToRayl,
                    onConnectionChanged = {
                        if (selection.external.value == ExternalWallet.Nwc) {
                            connected = it
                            if (it) selection.connected(ExternalWallet.Nwc)
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
                        if (selection.external.value == ExternalWallet.Spark) {
                            connected = it
                            if (it) selection.connected(ExternalWallet.Spark)
                        }
                    }
                )
            }
        }
        message?.let { resource ->
            AlertDialog(onDismissRequest = {
                message = null
            }, text = { Text(stringResource(resource)) }, confirmButton = {
                TextButton(onClick = { message = null }) { Text(stringResource(R.string.close)) }
            })
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
