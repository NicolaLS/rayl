package xyz.lilsus.rayl

import android.os.Bundle
import xyz.lilsus.rayl.providers.blink.integration.blink.TestBlinkApi
import xyz.lilsus.raylsuite.core.camera.TestQrInput

/**
 * The E2E app (qa/test-plan.md §6.8). Maestro's `launchApp` arguments arrive as intent extras:
 * - `scan`: what the payment screen "reads" once; the camera is never used.
 * - `blinkApiUrl`: the Blink backend, by default the local Blink stack (`tools/blink-stack`).
 */
class E2eMainActivity : MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        TestQrInput.install(
            intent.getStringExtra(SCAN_EXTRA).takeIf { savedInstanceState == null }
        )
        TestBlinkApi.install(intent.getStringExtra(BLINK_API_EXTRA) ?: LOCAL_BLINK_API)
        super.onCreate(savedInstanceState)
    }

    private companion object {
        const val SCAN_EXTRA = "scan"
        const val BLINK_API_EXTRA = "blinkApiUrl"

        // The emulator's address for the host machine; the stack publishes its API there.
        const val LOCAL_BLINK_API = "http://10.0.2.2:4475/graphql"
    }
}
