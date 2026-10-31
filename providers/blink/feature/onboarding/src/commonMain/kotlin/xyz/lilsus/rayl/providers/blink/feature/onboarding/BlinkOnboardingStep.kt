package xyz.lilsus.rayl.providers.blink.feature.onboarding

enum class BlinkOnboardingStep {
    Connect,
    Agreement,
    Complete;

    fun destination(walletConnected: Boolean): BlinkOnboardingStep = when {
        !walletConnected -> Connect
        this == Connect -> Agreement
        else -> this
    }
}
