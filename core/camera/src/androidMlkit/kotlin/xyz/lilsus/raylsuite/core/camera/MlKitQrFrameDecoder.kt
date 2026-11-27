package xyz.lilsus.raylsuite.core.camera

import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "QrScanner"

internal fun qrFrameDecoder(): QrFrameDecoder = MlKitQrFrameDecoder

/**
 * ML Kit's QR reader, one for the process. It loads its model when it reads its first image,
 * which takes longer than reading a frame, and unloads it when closed; a reader per scanner start
 * paid that on the first frame after every payment.
 */
private object MlKitQrFrameDecoder : QrFrameDecoder {
    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    private val warmedUp = AtomicBoolean(false)

    /** Loads the model by reading an empty image, off the caller's thread; once per process. */
    override fun warmUp() {
        if (!warmedUp.compareAndSet(false, true)) return
        try {
            val blank = Bitmap.createBitmap(
                WARM_UP_IMAGE_SIZE,
                WARM_UP_IMAGE_SIZE,
                Bitmap.Config.ARGB_8888
            )
            scanner.process(InputImage.fromBitmap(blank, 0))
                .addOnFailureListener { Log.w(TAG, "QR reader warm-up failed", it) }
        } catch (failure: RuntimeException) {
            // The first frame loads the model then, as it always did.
            Log.w(TAG, "QR reader warm-up failed", failure)
        }
    }

    @OptIn(ExperimentalGetImage::class)
    override fun decode(image: ImageProxy, onComplete: (List<String>?) -> Unit) {
        val mediaImage = image.image
        if (mediaImage == null) {
            onComplete(null)
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnCompleteListener(DirectExecutor) { task ->
                if (!task.isSuccessful) {
                    Log.e(TAG, "Barcode scanning failed", task.exception)
                    onComplete(null)
                    return@addOnCompleteListener
                }
                val (frameWidth, frameHeight) = rotatedFrameDimensions(image)
                onComplete(
                    rankBarcodeValues(
                        barcodes = task.result.orEmpty(),
                        frameWidth = frameWidth,
                        frameHeight = frameHeight
                    )
                )
            }
    }

    private fun rankBarcodeValues(
        barcodes: List<Barcode>,
        frameWidth: Float,
        frameHeight: Float
    ): List<String> {
        if (barcodes.isEmpty()) return emptyList()
        return rankQrValues(
            candidates = barcodes.mapNotNull { barcode ->
                val value = barcode.rawValue?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                // A code without a place in the frame ranks last.
                val bounds = barcode.boundingBox
                QrDetectionCandidate(
                    value = value,
                    left = bounds?.left?.toFloat() ?: 0f,
                    top = bounds?.top?.toFloat() ?: 0f,
                    right = bounds?.right?.toFloat() ?: 0f,
                    bottom = bounds?.bottom?.toFloat() ?: 0f
                )
            },
            frameWidth = frameWidth,
            frameHeight = frameHeight
        )
    }

    private object DirectExecutor : Executor {
        override fun execute(command: Runnable) {
            command.run()
        }
    }

    private const val WARM_UP_IMAGE_SIZE = 64
}
