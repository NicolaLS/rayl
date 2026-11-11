package xyz.lilsus.rayl.providers.nwc.feature.onboarding

import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

data class NativeNwcOnboardingText(val agreementBody: String)

suspend fun nativeNwcOnboardingText(appName: String): NativeNwcOnboardingText =
    NativeNwcOnboardingText(
        agreementBody = nativeString(
            NativeStringResource(table = "NwcOnboarding", key = "onboarding_agreement_body"),
            appName
        )
    )
