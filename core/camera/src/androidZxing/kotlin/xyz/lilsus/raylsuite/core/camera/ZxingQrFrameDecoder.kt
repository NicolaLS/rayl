package xyz.lilsus.raylsuite.core.camera

import android.util.Log
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean
import zxingcpp.BarcodeReader

private const val TAG = "QrScanner"

internal fun qrFrameDecoder(): QrFrameDecoder = ZxingQrFrameDecoder

/**
 * Open-source QR reader backed by zxing-cpp, selected via `-Prayl.scanner=zxing` for Android
 * builds without Google ML Kit.
 */
private object ZxingQrFrameDecoder : QrFrameDecoder {
    private val reader by lazy {
        BarcodeReader(
            BarcodeReader.Options(
                formats = setOf(BarcodeReader.Format.QR_CODE),
                tryHarder = true,
                tryRotate = true,
                tryInvert = true,
                tryDownscale = true
            )
        )
    }
    private val warmedUp = AtomicBoolean(false)

    /** Loads the native library while the camera opens instead of on the first frame. */
    override fun warmUp() {
        if (!warmedUp.compareAndSet(false, true)) return
        try {
            reader
        } catch (failure: Throwable) {
            Log.w(TAG, "QR reader warm-up failed", failure)
        }
    }

    override fun decode(image: ImageProxy, onComplete: (List<String>?) -> Unit) {
        val results = try {
            reader.read(image)
        } catch (failure: Throwable) {
            Log.e(TAG, "Barcode scanning failed", failure)
            onComplete(null)
            return
        }
        if (results.isEmpty()) {
            onComplete(emptyList())
            return
        }
        val (frameWidth, frameHeight) = rotatedFrameDimensions(image)
        val candidates = results.mapNotNull { result ->
            val value = result.text?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val position = result.position
            val left = minOf(
                position.topLeft.x,
                position.topRight.x,
                position.bottomRight.x,
                position.bottomLeft.x
            ).toFloat()
            val top = minOf(
                position.topLeft.y,
                position.topRight.y,
                position.bottomRight.y,
                position.bottomLeft.y
            ).toFloat()
            val right = maxOf(
                position.topLeft.x,
                position.topRight.x,
                position.bottomRight.x,
                position.bottomLeft.x
            ).toFloat()
            val bottom = maxOf(
                position.topLeft.y,
                position.topRight.y,
                position.bottomRight.y,
                position.bottomLeft.y
            ).toFloat()
            QrDetectionCandidate(
                value = value,
                left = left,
                top = top,
                right = right,
                bottom = bottom
            )
        }
        onComplete(
            rankQrValues(
                candidates = candidates,
                frameWidth = frameWidth,
                frameHeight = frameHeight
            )
        )
    }
}
