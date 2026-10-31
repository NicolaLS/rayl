package xyz.lilsus.rayl.providers.nwc.feature.onboarding

import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

data class NativeNwcOnboardingText(
    val agreementBody: String,
    val instructionsTitle: String,
    val instructionsIntro: String,
    val instructionSteps: List<String>
)

suspend fun nativeNwcOnboardingText(appName: String): NativeNwcOnboardingText =
    NativeNwcOnboardingText(
        agreementBody = nativeString(
            NativeStringResource(table = "NwcOnboarding", key = "onboarding_agreement_body"),
            appName
        ),
        instructionsTitle = nativeString(
            NativeStringResource(table = "NwcOnboarding", key = "onboarding_add_wallet_title")
        ),
        instructionsIntro = nativeString(
            NativeStringResource(table = "NwcOnboarding", key = "onboarding_add_wallet_intro"),
            appName
        ),
        instructionSteps =
            listOf(
                nativeString(
                    NativeStringResource(
                        table = "NwcOnboarding",
                        key = "onboarding_add_wallet_step1"
                    ),
                    appName
                ),
                nativeString(
                    NativeStringResource(
                        table = "NwcOnboarding",
                        key = "onboarding_add_wallet_step2"
                    )
                ),
                nativeString(
                    NativeStringResource(
                        table = "NwcOnboarding",
                        key = "onboarding_add_wallet_step3"
                    )
                )
            )
    )
