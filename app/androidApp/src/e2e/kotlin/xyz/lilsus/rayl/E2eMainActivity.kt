package xyz.lilsus.rayl

import android.os.Bundle
import xyz.lilsus.raylsuite.core.camera.TestQrInput

class E2eMainActivity : MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The E2E app never uses the camera. Maestro's `launchApp` arguments arrive as intent
        // extras: `scan` is what the payment screen "reads" once (qa/test-plan.md §6.8).
        TestQrInput.install(
            intent.getStringExtra(SCAN_EXTRA).takeIf { savedInstanceState == null }
        )
        super.onCreate(savedInstanceState)
    }

    private companion object {
        const val SCAN_EXTRA = "scan"
    }
}
