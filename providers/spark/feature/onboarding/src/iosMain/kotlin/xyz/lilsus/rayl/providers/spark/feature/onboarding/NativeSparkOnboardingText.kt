package xyz.lilsus.rayl.providers.spark.feature.onboarding

import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

data class NativeSparkOnboardingText(
    val agreementBody: String,
    val instructionsTitle: String,
    val instructionsIntro: String,
    val instructionSteps: List<String>
)

suspend fun nativeSparkOnboardingText(appName: String): NativeSparkOnboardingText =
    NativeSparkOnboardingText(
        agreementBody = nativeString(
            NativeStringResource(
                table = "SparkOnboarding",
                key = "spark_onboarding_agreement_body"
            ),
            appName
        ),
        instructionsTitle = nativeString(
            NativeStringResource(
                table = "SparkOnboarding",
                key = "spark_onboarding_add_wallet_title"
            )
        ),
        instructionsIntro = nativeString(
            NativeStringResource(
                table = "SparkOnboarding",
                key = "spark_onboarding_add_wallet_intro"
            ),
            appName
        ),
        instructionSteps =
            listOf(
                nativeString(
                    NativeStringResource(
                        table = "SparkOnboarding",
                        key = "spark_onboarding_add_wallet_step1"
                    )
                ),
                nativeString(
                    NativeStringResource(
                        table = "SparkOnboarding",
                        key = "spark_onboarding_add_wallet_step2"
                    ),
                    appName
                ),
                nativeString(
                    NativeStringResource(
                        table = "SparkOnboarding",
                        key = "spark_onboarding_add_wallet_step3"
                    ),
                    appName
                )
            )
    )
