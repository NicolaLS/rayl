package xyz.lilsus.rayl

import xyz.lilsus.rayl.providers.blink.integration.blink.TestBlinkApi
import xyz.lilsus.raylsuite.core.camera.TestQrInput

// For the E2E app only (`iosAppE2E`, qa/test-plan.md §6.8). The store app never calls these.

/** Replaces the camera: the payment screen "reads" [value] once. See [TestQrInput]. */
fun installE2eScanInput(value: String?) = TestQrInput.install(value)

/** Points Blink wallets at [serverUrl], the local Blink stack by default. See [TestBlinkApi]. */
fun installE2eBlinkApi(serverUrl: String) = TestBlinkApi.install(serverUrl)
