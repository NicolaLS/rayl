package xyz.lilsus.rayl

import android.app.Application
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import xyz.lilsus.rayl.providers.rayl.RaylPasskeys
import xyz.lilsus.rayl.providers.rayl.enableRaylTimings
import xyz.lilsus.rayl.providers.spark.SparkEnvironment
import xyz.lilsus.raylsuite.core.network.initializeNetworkConnectivity

class RaylApplication :
    Application(),
    CameraXConfig.Provider {

    override fun getCameraXConfig(): CameraXConfig = CameraXConfig.Builder
        .fromConfig(Camera2Config.defaultConfig())
        .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
        .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableRaylTimings()
        initializeNetworkConnectivity(this)
        val configuration = androidRaylConfiguration()
        startConfiguredWalletEarly(this, configuration.environment, configuration.breezApiKey)
    }
}

internal data class AndroidRaylConfiguration(
    val environment: SparkEnvironment,
    val passkeys: RaylPasskeys,
    val breezApiKey: String? = null
)

/**
 * The relying party for passkeys in development builds; release builds use Breez's. Its
 * `/.well-known/assetlinks.json` lists the development packages and their signing certificates;
 * the site lives in the nicolasusca.com repository. The apex domain redirects to `www`, and
 * association files must not redirect.
 */
internal val DEVELOPMENT_PASSKEYS = RaylPasskeys.Platform(rpId = "www.nicolasusca.com")
