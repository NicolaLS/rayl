package xyz.lilsus.raylsuite.feature.onboarding

import xyz.lilsus.raylsuite.core.ui.nativeBackActionText
import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

data class NativeOnboardingText(
    val back: String,
    val autoPayTitle: String,
    val autoPayAlways: String,
    val autoPayThreshold: String,
    val autoPayHint: String,
    val autoPayContinue: String,
    val agreementTitle: String,
    val agreementCheckbox: String,
    val agreementContinue: String
)

suspend fun nativeOnboardingText(): NativeOnboardingText = NativeOnboardingText(
    back = nativeBackActionText(),
    autoPayTitle = nativeString(
        NativeStringResource(table = "Onboarding", key = "onboarding_autopay_title")
    ),
    autoPayAlways = nativeString(
        NativeStringResource(table = "Onboarding", key = "onboarding_autopay_always")
    ),
    autoPayThreshold = nativeString(
        NativeStringResource(table = "Onboarding", key = "onboarding_autopay_threshold")
    ),
    autoPayHint = nativeString(
        NativeStringResource(table = "Onboarding", key = "onboarding_autopay_hint")
    ),
    autoPayContinue = nativeString(
        NativeStringResource(table = "Onboarding", key = "onboarding_autopay_continue")
    ),
    agreementTitle = nativeString(
        NativeStringResource(table = "Onboarding", key = "onboarding_agreement_title")
    ),
    agreementCheckbox = nativeString(
        NativeStringResource(table = "Onboarding", key = "onboarding_agreement_checkbox")
    ),
    agreementContinue = nativeString(
        NativeStringResource(table = "Onboarding", key = "onboarding_agreement_continue")
    )
)

suspend fun nativeOnboardingThresholdLabel(amount: String): String = nativeString(
    NativeStringResource(table = "Onboarding", key = "onboarding_autopay_threshold_label"),
    amount
)
