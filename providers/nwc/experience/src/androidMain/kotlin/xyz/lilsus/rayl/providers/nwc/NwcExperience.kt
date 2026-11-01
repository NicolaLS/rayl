package xyz.lilsus.rayl.providers.nwc

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import kotlin.random.Random
import xyz.lilsus.rayl.providers.nwc.feature.onboarding.NwcOnboardingDestination
import xyz.lilsus.rayl.providers.nwc.feature.onboarding.nwcOnboarding
import xyz.lilsus.raylsuite.core.model.ThemePreference
import xyz.lilsus.raylsuite.core.model.WalletStartMode
import xyz.lilsus.raylsuite.core.settings.rememberAppSettings
import xyz.lilsus.raylsuite.core.settings.rememberSecureSettings
import xyz.lilsus.raylsuite.core.ui.platform.LocalProductName
import xyz.lilsus.raylsuite.core.ui.platform.rememberCredentialClipboard
import xyz.lilsus.raylsuite.core.ui.platform.rememberHapticFeedbackManager
import xyz.lilsus.raylsuite.core.ui.platform.rememberRetainedInstance
import xyz.lilsus.raylsuite.core.ui.theme.RaylSuiteTheme
import xyz.lilsus.raylsuite.feature.walletmanagement.WalletRemovalFailure

/** The Compose host used on Android. iOS drives the same content from a native shell. */
@Composable
fun NwcExperience(
    configuration: NwcExperienceConfiguration,
    startMode: WalletStartMode,
    onRemoved: () -> Unit,
    onConnectionChanged: (Boolean) -> Unit
) {
    val appSettings = rememberAppSettings(configuration.preferencesName)
    val secureSettings = rememberSecureSettings(configuration.credentialsName)
    val walletSettings = rememberAppSettings(configuration.walletPreferencesName)
    val haptics = rememberHapticFeedbackManager()
    val runtimeKey = rememberSaveable { "nwc-" + Random.nextLong() }
    val runtime = rememberRetainedInstance(
        key = runtimeKey,
        factory = {
            NwcRuntime(
                appSettings,
                walletSettings,
                configuration,
                secureSettings,
                haptics,
                startMode
            )
        },
        onDispose = NwcRuntime::clear,
        releaseOnLeave = true
    )
    LaunchedEffect(runtime) {
        runtime.removed.collect { if (it) onRemoved() }
    }
    val themePreference by runtime.themePreferences.preference.collectAsStateWithLifecycle(
        initialValue = ThemePreference.System
    )
    val removalPending by runtime.removalPending.collectAsStateWithLifecycle()
    val removalFailed by runtime.removalFailed.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalProductName provides configuration.appName) {
        RaylSuiteTheme(themePreference = themePreference) {
            if (removalPending) {
                LaunchedEffect(runtime) { onConnectionChanged(false) }
                WalletRemovalFailure(
                    isWorking = !removalFailed,
                    onRetry = runtime::removeWallet
                )
            } else {
                NwcExperienceContent(
                    runtime,
                    onConnectionChanged
                )
            }
        }
    }
}

@Composable
private fun NwcExperienceContent(runtime: NwcRuntime, onConnectionChanged: (Boolean) -> Unit) {
    val connected by runtime.nwcWallet.connection.collectAsStateWithLifecycle()
    LaunchedEffect(runtime, connected) { onConnectionChanged(connected != null) }
    val clipboard = rememberCredentialClipboard()
    val connectionOnly = remember(runtime) { runtime.onboardingState.completed.value }
    val navController = rememberNavController()
    val startDestination =
        remember(runtime) {
            if (runtime.onboardingState.completed.value &&
                runtime.nwcWallet.connection.value != null
            ) {
                NwcDestination.Home
            } else if (runtime.nwcWallet.connection.value == null) {
                NwcOnboardingDestination.AddWallet
            } else {
                NwcOnboardingDestination.Agreement
            }
        }

    val cancellationInsets = if (connected == null) {
        Modifier.windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        )
    } else {
        Modifier
    }
    Column(modifier = cancellationInsets) {
        if (connected == null) {
            TextButton(
                onClick = {
                    if (runtime.nwcWallet.connection.value == null &&
                        !runtime.removalPending.value
                    ) {
                        runtime.removeWallet()
                    }
                }
            ) { Text(stringResource(android.R.string.cancel)) }
        }
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
        ) {
            nwcOnboarding(
                navController = navController,
                nwcWallet = runtime.nwcWallet,
                onboardingViewModel = runtime.onboardingViewModel,
                connectionDraft = runtime.connectionDraft,
                clipboard = clipboard,
                connectionOnly = connectionOnly,
                privacyPolicyUrl = runtime.configuration.legalLinks.privacyPolicyUrl,
                termsUrl = runtime.configuration.legalLinks.termsUrl,
                onWalletConnected = {
                    runtime.completeOnboarding()
                    navController.navigate(NwcDestination.Home) {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
            nwcHome(runtime = runtime)
        }
    }
}
