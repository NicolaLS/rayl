package xyz.lilsus.rayl.providers.spark.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import xyz.lilsus.rayl.providers.spark.application.wallet.WalletAccessState
import xyz.lilsus.rayl.providers.spark.feature.onboarding.R
import xyz.lilsus.rayl.providers.spark.feature.walletconnection.WalletConnectionScreen
import xyz.lilsus.rayl.providers.spark.feature.walletconnection.WalletViewModel
import xyz.lilsus.raylsuite.feature.onboarding.AgreementScreen
import xyz.lilsus.raylsuite.feature.onboarding.OnboardingViewModel

@Serializable
sealed interface SparkOnboardingDestination {
    @Serializable
    data object Agreement : SparkOnboardingDestination

    @Serializable
    data object AddWallet : SparkOnboardingDestination

    @Serializable
    data object WalletRecovery : SparkOnboardingDestination
}

fun NavGraphBuilder.sparkOnboarding(
    navController: NavController,
    onboardingViewModel: OnboardingViewModel,
    walletViewModel: WalletViewModel,
    connectionOnly: Boolean = false,
    privacyPolicyUrl: String? = null,
    termsUrl: String? = null,
    onCancel: (() -> Unit)? = null,
    onWalletConnected: () -> Unit
) {
    composable<SparkOnboardingDestination.Agreement> {
        val state by onboardingViewModel.uiState.collectAsStateWithLifecycle()
        AgreementScreen(
            body = stringResource(
                R.string.spark_onboarding_agreement_body,
                xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
            ),
            hasAgreed = state.hasAgreed,
            onAgreementChanged = onboardingViewModel::setAgreement,
            onContinue = {
                if (state.hasAgreed) onWalletConnected()
            },
            onBack = onCancel
        )
    }
    composable<SparkOnboardingDestination.AddWallet> {
        OnboardingWalletDestination(
            navController = navController,
            walletViewModel = walletViewModel,
            connectionOnly = connectionOnly,
            privacyPolicyUrl = privacyPolicyUrl,
            termsUrl = termsUrl,
            onCancel = onCancel,
            onWalletConnected = onWalletConnected
        )
    }
    composable<SparkOnboardingDestination.WalletRecovery> {
        val state by walletViewModel.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.access) {
            when (state.access) {
                WalletAccessState.Connected -> {
                    if (connectionOnly) {
                        onWalletConnected()
                    } else {
                        navController.navigate(SparkOnboardingDestination.Agreement) {
                            popUpTo(navController.graph.id) { inclusive = true }
                        }
                    }
                }

                WalletAccessState.NoWallet -> {
                    navController.navigate(SparkOnboardingDestination.AddWallet) {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                }

                else -> Unit
            }
        }
        WalletConnectionScreen(
            state = state,
            onBack = onCancel,
            dispatch = walletViewModel::dispatch
        )
    }
}

@Composable
private fun OnboardingWalletDestination(
    navController: NavController,
    walletViewModel: WalletViewModel,
    connectionOnly: Boolean,
    privacyPolicyUrl: String?,
    termsUrl: String?,
    onCancel: (() -> Unit)?,
    onWalletConnected: () -> Unit
) {
    val state by walletViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.access) {
        if (state.access == WalletAccessState.Connected) {
            if (connectionOnly) {
                onWalletConnected()
            } else {
                navController.navigate(SparkOnboardingDestination.Agreement) {
                    popUpTo(navController.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }
    WalletConnectionScreen(
        state = state,
        onBack = onCancel,
        dispatch = walletViewModel::dispatch,
        privacyPolicyUrl = privacyPolicyUrl,
        termsUrl = termsUrl
    )
}
