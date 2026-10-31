package xyz.lilsus.rayl.providers.spark.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
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
import xyz.lilsus.raylsuite.feature.onboarding.WalletInstructionsScreen

@Serializable
sealed interface SparkOnboardingDestination {
    @Serializable
    data object Agreement : SparkOnboardingDestination

    @Serializable
    data object WalletInstructions : SparkOnboardingDestination

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
            stepIndex = 0,
            totalSteps = 0,
            onAgreementChanged = onboardingViewModel::setAgreement,
            onContinue = {
                if (state.hasAgreed) onWalletConnected()
            },
            onBack = null
        )
    }
    composable<SparkOnboardingDestination.WalletInstructions> {
        WalletInstructionsScreen(
            title = stringResource(R.string.spark_onboarding_add_wallet_title),
            introduction = stringResource(
                R.string.spark_onboarding_add_wallet_intro,
                xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
            ),
            steps =
                listOf(
                    AnnotatedString(stringResource(R.string.spark_onboarding_add_wallet_step1)),
                    AnnotatedString(
                        stringResource(
                            R.string.spark_onboarding_add_wallet_step2,
                            xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
                        )
                    ),
                    AnnotatedString(
                        stringResource(
                            R.string.spark_onboarding_add_wallet_step3,
                            xyz.lilsus.raylsuite.core.ui.platform.LocalProductName.current
                        )
                    )
                ),
            stepIndex = 0,
            totalSteps = 0,
            onConnectWallet = { navController.navigateUp() },
            onBack = navController::navigateUp
        )
    }
    composable<SparkOnboardingDestination.AddWallet> {
        OnboardingWalletDestination(
            navController = navController,
            walletViewModel = walletViewModel,
            connectionOnly = connectionOnly,
            privacyPolicyUrl = privacyPolicyUrl,
            termsUrl = termsUrl,
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
            onBack = null,
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
        onBack = null,
        dispatch = walletViewModel::dispatch,
        onShowInstructions = {
            navController.navigate(SparkOnboardingDestination.WalletInstructions)
        },
        privacyPolicyUrl = privacyPolicyUrl,
        termsUrl = termsUrl
    )
}
