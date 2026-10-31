package xyz.lilsus.rayl.providers.blink.feature.onboarding

import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

data class NativeBlinkOnboardingPage(
    val title: String,
    val subtitle: String,
    val body: String,
    val imageName: String?
)

data class NativeBlinkOnboardingText(
    val agreementBody: String,
    val instructionsTitle: String,
    val instructionsIntro: String,
    val instructions: List<NativeBlinkOnboardingPage>,
    val previousStep: String,
    val nextStep: String,
    val dashboardButton: String,
    val enterKeyButton: String
)

suspend fun nativeBlinkOnboardingText(appName: String): NativeBlinkOnboardingText =
    NativeBlinkOnboardingText(
        agreementBody = nativeString(
            NativeStringResource(
                table = "BlinkOnboarding",
                key = "blink_onboarding_agreement_body"
            ),
            appName
        ),
        instructionsTitle = nativeString(
            NativeStringResource(
                table = "BlinkOnboarding",
                key = "blink_onboarding_add_wallet_title"
            )
        ),
        instructionsIntro = nativeString(
            NativeStringResource(
                table = "BlinkOnboarding",
                key = "blink_onboarding_add_wallet_intro"
            ),
            appName
        ),
        instructions =
            listOf(
                instruction(
                    title = nativeString(
                        NativeStringResource(
                            table = "BlinkOnboarding",
                            key = "blink_onboarding_add_wallet_step1_title"
                        )
                    ),
                    body = nativeString(
                        NativeStringResource(
                            table = "BlinkOnboarding",
                            key = "blink_onboarding_add_wallet_step1_body"
                        )
                    ),
                    imageName = "blink_dashboard_email"
                ),
                instruction(
                    title = nativeString(
                        NativeStringResource(
                            table = "BlinkOnboarding",
                            key = "blink_onboarding_add_wallet_step2_title"
                        )
                    ),
                    body = nativeString(
                        NativeStringResource(
                            table = "BlinkOnboarding",
                            key = "blink_onboarding_add_wallet_step2_body"
                        )
                    ),
                    imageName = "blink_dashboard_api_keys"
                ),
                instruction(
                    title = nativeString(
                        NativeStringResource(
                            table = "BlinkOnboarding",
                            key = "blink_onboarding_add_wallet_step3_title"
                        )
                    ),
                    body = nativeString(
                        NativeStringResource(
                            table = "BlinkOnboarding",
                            key = "blink_onboarding_add_wallet_step3_body"
                        )
                    ),
                    imageName = "blink_dashboard_key_settings"
                ),
                instruction(
                    title = nativeString(
                        NativeStringResource(
                            table = "BlinkOnboarding",
                            key = "blink_onboarding_add_wallet_step4_title"
                        )
                    ),
                    body = nativeString(
                        NativeStringResource(
                            table = "BlinkOnboarding",
                            key = "blink_onboarding_add_wallet_step4_body"
                        ),
                        appName
                    ),
                    imageName = "blink_dashboard_copy_key"
                )
            ),
        previousStep = nativeString(
            NativeStringResource(
                table = "BlinkOnboarding",
                key = "blink_onboarding_add_wallet_previous_step"
            )
        ),
        nextStep = nativeString(
            NativeStringResource(
                table = "BlinkOnboarding",
                key = "blink_onboarding_add_wallet_next_step"
            )
        ),
        dashboardButton = nativeString(
            NativeStringResource(
                table = "BlinkOnboarding",
                key = "blink_onboarding_add_wallet_dashboard_button"
            )
        ),
        enterKeyButton = nativeString(
            NativeStringResource(
                table = "BlinkOnboarding",
                key = "blink_onboarding_add_wallet_enter_key_button"
            )
        )
    )

suspend fun nativeBlinkInstructionProgress(step: Int, total: Int): String = nativeString(
    NativeStringResource(
        table = "BlinkOnboarding",
        key = "blink_onboarding_add_wallet_step_progress"
    ),
    step,
    total
)

private fun instruction(title: String, body: String, imageName: String) = NativeBlinkOnboardingPage(
    title = title,
    subtitle = "",
    body = body,
    imageName = imageName
)
