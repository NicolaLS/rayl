package xyz.lilsus.rayl.providers.rayl

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.lang.ref.WeakReference
import kotlin.random.Random
import xyz.lilsus.rayl.providers.rayl.experience.R
import xyz.lilsus.rayl.providers.rayl.integration.pocket.PocketStatus
import xyz.lilsus.rayl.providers.rayl.integration.pocket.createAndroidPocketSetup
import xyz.lilsus.rayl.providers.rayl.integration.pocket.createAndroidRaylPocket
import xyz.lilsus.raylsuite.core.model.ThemePreference
import xyz.lilsus.raylsuite.core.settings.rememberAppSettings
import xyz.lilsus.raylsuite.core.ui.platform.LocalProductName
import xyz.lilsus.raylsuite.core.ui.platform.rememberHapticFeedbackManager
import xyz.lilsus.raylsuite.core.ui.platform.rememberRetainedInstance
import xyz.lilsus.raylsuite.core.ui.theme.RaylSuiteTheme

/**
 * The Compose host of Rayl's built-in wallet on Android. It needs no credentials: mounting it
 * starts the pocket, and its onboarding sets up the passkey that derives the pocket key. [onUseWallet] runs with one of [otherWallets] once the pocket session has
 * closed, so the host can set up that wallet; [onErased] after a debug build erased the pocket.
 * [walletIcons] are drawables for [otherWallets] by id; a wallet without one shows its initial.
 */
@Composable
fun RaylExperience(
    configuration: RaylExperienceConfiguration,
    environment: RaylEnvironment,
    breezApiKey: String?,
    passkeys: RaylPasskeys,
    debugTools: Boolean,
    otherWallets: List<RaylWalletOption>,
    onUseWallet: (String) -> Unit,
    onErased: () -> Unit,
    onConnectionChanged: (Boolean) -> Unit,
    walletIcons: Map<String, Int> = emptyMap()
) {
    val appSettings = rememberAppSettings(configuration.preferencesName)
    val haptics = rememberHapticFeedbackManager()
    val context = LocalContext.current.applicationContext
    // Before the runtime: its onboarding checks passkey support with this activity right away.
    RaylPasskeyActivity.Track()
    val runtimeKey = rememberSaveable { "rayl-" + Random.nextLong() }
    val runtime = rememberRetainedInstance(
        key = runtimeKey,
        factory = {
            RaylRuntime(
                appSettings = appSettings,
                configuration = configuration,
                debugTools = debugTools,
                pocket = RaylEarlyStart.take {
                    createAndroidRaylPocket(context, environment.pocketConfig(breezApiKey))
                },
                createSetup = { pocket ->
                    createAndroidPocketSetup(
                        context = context,
                        config = environment.pocketConfig(breezApiKey),
                        pocket = pocket,
                        rpId = (passkeys as? RaylPasskeys.Platform)?.rpId,
                        activity = RaylPasskeyActivity::get
                    )
                },
                haptics = haptics,
                onFundingInvoice = { invoice ->
                    // Read by `tools/regtest/regtest pay --from android`.
                    val regtest = environment == RaylEnvironment.DEBUG
                    if (regtest) Log.i(REGTEST_LOG_TAG, "invoice $invoice")
                }
            )
        },
        onDispose = RaylRuntime::clear,
        releaseOnLeave = true
    )
    val themePreference by runtime.themePreferences.preference.collectAsStateWithLifecycle(
        initialValue = ThemePreference.System
    )
    val status by runtime.pocket.status.collectAsStateWithLifecycle()
    val exit by runtime.exit.collectAsStateWithLifecycle()
    val onboardingStep by runtime.onboardingState.step.collectAsStateWithLifecycle()
    LaunchedEffect(runtime, status, exit) {
        onConnectionChanged(status == PocketStatus.Ready && exit == RaylExit.None)
        if (exit == RaylExit.Done) {
            when (val target = runtime.exitTarget) {
                is RaylExitTarget.Wallet -> onUseWallet(target.id)
                RaylExitTarget.Erased -> onErased()
                null -> Unit
            }
        }
    }
    CompositionLocalProvider(
        LocalProductName provides configuration.appName,
        LocalRaylWalletIcons provides walletIcons
    ) {
        RaylSuiteTheme(themePreference = themePreference) {
            when {
                exit == RaylExit.Failed -> RaylStatusScreen(
                    message = stringResource(R.string.rayl_leave_failed),
                    action = stringResource(R.string.rayl_retry),
                    onAction = runtime::retryLeave
                )

                exit != RaylExit.None -> RaylStatusScreen(message = null)

                onboardingStep != RaylOnboardingStep.Complete -> RaylOnboarding(
                    runtime,
                    otherWallets
                )

                // The pocket connects in the background: the camera must not wait for it.
                else -> RaylTabs(runtime, otherWallets)
            }
        }
    }
}

@Composable
internal fun RaylStatusScreen(message: String?, action: String? = null, onAction: () -> Unit = {}) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
        ) {
            if (action == null) CircularProgressIndicator()
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
            action?.let { TextButton(onClick = onAction) { Text(it) } }
        }
    }
}

/** The host's drawables for the wallets it offers, by wallet id. */
internal val LocalRaylWalletIcons = staticCompositionLocalOf<Map<String, Int>> { emptyMap() }

private const val REGTEST_LOG_TAG = "RaylRegtest"

/** Call from `Application.onCreate` when Rayl's wallet is in use. */
fun startRaylPocketEarly(context: Context, environment: RaylEnvironment, breezApiKey: String?) {
    RaylEarlyStart.start {
        createAndroidRaylPocket(context.applicationContext, environment.pocketConfig(breezApiKey))
    }
}

/**
 * The activity passkey prompts appear over. Credential Manager needs the resumed one, and the
 * runtime outlives activities across configuration changes.
 */
private object RaylPasskeyActivity {
    @Volatile private var current: WeakReference<Activity>? = null

    fun get(): Activity? = current?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    /** Remembers the composing activity; a remounted experience keeps using the same one. */
    @Composable
    fun Track() {
        val activity = LocalActivity.current
        if (activity != null && current?.get() !== activity) current = WeakReference(activity)
    }
}
