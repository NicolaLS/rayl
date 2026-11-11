package xyz.lilsus.rayl.providers.spark.feature.onboarding

import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

data class NativeSparkOnboardingText(val agreementBody: String)

suspend fun nativeSparkOnboardingText(appName: String): NativeSparkOnboardingText =
    NativeSparkOnboardingText(
        agreementBody = nativeString(
            NativeStringResource(
                table = "SparkOnboarding",
                key = "spark_onboarding_agreement_body"
            ),
            appName
        )
    )
