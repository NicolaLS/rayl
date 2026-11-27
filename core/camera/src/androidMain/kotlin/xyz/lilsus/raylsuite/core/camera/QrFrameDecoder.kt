package xyz.lilsus.raylsuite.core.camera

import androidx.camera.core.ImageProxy

/**
 * Decodes QR codes from one CameraX frame. The Android build selects either ML Kit (default) or
 * open-source zxing-cpp (`-Prayl.scanner=zxing`).
 */
internal interface QrFrameDecoder {
    /** Prepares any native model before the first frame arrives, once per process. */
    fun warmUp()

    /**
     * Decodes [image] and delivers ranked QR values (or `null` when decoding failed or the frame
     * could not be read). [image] stays open until [onComplete] returns.
     */
    fun decode(image: ImageProxy, onComplete: (List<String>?) -> Unit)
}

internal fun rotatedFrameDimensions(image: ImageProxy): Pair<Float, Float> {
    val rotation = image.imageInfo.rotationDegrees
    return if (rotation == 90 || rotation == 270) {
        image.height.toFloat() to image.width.toFloat()
    } else {
        image.width.toFloat() to image.height.toFloat()
    }
}
