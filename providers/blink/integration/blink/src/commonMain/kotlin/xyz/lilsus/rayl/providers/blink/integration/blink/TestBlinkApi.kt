package xyz.lilsus.rayl.providers.blink.integration.blink

/**
 * Points Blink wallets at a test backend (the local Blink stack, `tools/blink-stack`) instead of
 * Blink's API. Only the E2E app entry points call [install]; in every other build Blink wallets
 * use Blink's production API.
 */
object TestBlinkApi {
    internal var serverUrl: String? = null
        private set

    fun install(serverUrl: String) {
        this.serverUrl = serverUrl
    }
}
