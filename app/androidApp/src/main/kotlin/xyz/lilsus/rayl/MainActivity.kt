package xyz.lilsus.rayl

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import xyz.lilsus.raylsuite.core.ui.orientation.CompactWindowOrientationPolicy

open class MainActivity : AppCompatActivity() {
    private val orientationPolicy = CompactWindowOrientationPolicy(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        orientationPolicy.apply()

        val configuration = androidRaylConfiguration()
        setContent {
            App(
                sparkEnvironment = configuration.environment,
                raylPasskeys = configuration.passkeys,
                debugTools = BuildConfig.DEBUG,
                breezApiKey = configuration.breezApiKey
            )
        }

        orientationPolicy.startListening()
    }

    override fun onDestroy() {
        orientationPolicy.stopListening()
        super.onDestroy()
    }
}
