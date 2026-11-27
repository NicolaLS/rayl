package xyz.lilsus.rayl

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import xyz.lilsus.raylsuite.core.ui.orientation.CompactWindowOrientationPolicy
import xyz.lilsus.raylsuite.core.ui.platform.LocalScreenCaptureProtectionEnabled
import xyz.lilsus.raylsuite.core.ui.platform.enableTestTagsAsResourceId

open class MainActivity : AppCompatActivity() {
    private val orientationPolicy = CompactWindowOrientationPolicy(this)

    /**
     * Protects screens that show or accept secrets (recovery phrases, API keys, NWC URIs) with
     * `FLAG_SECURE` in release builds, while leaving normal screens and debug/E2E builds
     * capturable for remote testing and store review (QA PR-11 / O-05).
     */
    protected open val protectSensitiveScreens: Boolean
        get() = !BuildConfig.DEBUG

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        orientationPolicy.apply()

        val configuration = androidRaylConfiguration()
        setContent {
            CompositionLocalProvider(
                LocalScreenCaptureProtectionEnabled provides protectSensitiveScreens
            ) {
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
        }

        orientationPolicy.startListening()
    }

    override fun onDestroy() {
        orientationPolicy.stopListening()
        super.onDestroy()
    }
}
