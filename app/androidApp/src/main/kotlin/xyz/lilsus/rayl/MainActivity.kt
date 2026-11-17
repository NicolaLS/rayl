package xyz.lilsus.rayl

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import xyz.lilsus.raylsuite.core.ui.orientation.CompactWindowOrientationPolicy
import xyz.lilsus.raylsuite.core.ui.platform.enableTestTagsAsResourceId

open class MainActivity : AppCompatActivity() {
    private val orientationPolicy = CompactWindowOrientationPolicy(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // No FLAG_SECURE, including on the recovery-phrase, API-key and NWC URI screens (QA
        // O-05, accepted 2026-11-02): it blocks testing on remote devices (App Live) and slows
        // store review. A protection strategy that keeps both possible is still to be found,
        // e.g. protecting only credential screens in release builds.

        orientationPolicy.apply()

        val configuration = androidRaylConfiguration()
        setContent {
            // Test tags become resource IDs for every screen in this window, so UI automation
            // (Maestro) finds them; dialogs and sheets are separate windows and opt in themselves.
            Box(Modifier.enableTestTagsAsResourceId()) {
                App(
                    sparkEnvironment = configuration.environment,
                    raylPasskeys = configuration.passkeys,
                    debugTools = BuildConfig.DEBUG,
                    breezApiKey = configuration.breezApiKey
                )
            }
        }

        orientationPolicy.startListening()
    }

    override fun onDestroy() {
        orientationPolicy.stopListening()
        super.onDestroy()
    }
}
