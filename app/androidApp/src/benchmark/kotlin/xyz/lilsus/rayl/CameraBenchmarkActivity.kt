package xyz.lilsus.rayl

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.DisposableEffect
import xyz.lilsus.raylsuite.core.camera.rememberQrScannerController

/** Release-like, wallet-free entry point used only by the external macrobenchmark. */
class CameraBenchmarkActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scannerController = rememberQrScannerController()

            DisposableEffect(scannerController) {
                scannerController.start(onQrCodesScanned = {})
                onDispose(scannerController::stop)
            }
        }
    }
}
