package xyz.lilsus.rayl

import xyz.lilsus.raylsuite.core.camera.TestQrInput

/**
 * For the E2E app only (`iosAppE2E`): replaces the camera, and the payment screen "reads" [value]
 * once. The store app never calls it. See [TestQrInput].
 */
fun installE2eScanInput(value: String?) = TestQrInput.install(value)
