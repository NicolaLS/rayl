package xyz.lilsus.raylsuite.core.camera

import android.Manifest
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import xyz.lilsus.raylsuite.core.raylapi.TelemetryScanDecoder
import xyz.lilsus.raylsuite.core.telemetry.RaylTrace
import xyz.lilsus.raylsuite.core.telemetry.TraceInterval
import xyz.lilsus.raylsuite.core.telemetry.TracePoint
import xyz.lilsus.raylsuite.core.telemetry.TraceSpan

private const val TAG = "QrScanner"
private const val ANALYSIS_EXECUTOR_SHUTDOWN_DELAY_MILLIS = 2_000L

@Composable
fun rememberQrScannerController(): QrScannerController {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    return remember(context.applicationContext, lifecycleOwner) {
        TestQrInput.scannerOrNull(::postToMainThread) ?: AndroidQrScannerController(
            context = context.applicationContext,
            lifecycleOwner = lifecycleOwner
        )
    }
}

private fun postToMainThread(delayMillis: Long, block: () -> Unit): TestQrInput.Cancel {
    val handler = Handler(Looper.getMainLooper())
    val runnable = Runnable(block)
    handler.postDelayed(runnable, delayMillis)
    return TestQrInput.Cancel { handler.removeCallbacks(runnable) }
}

private class AndroidQrScannerController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) : QrScannerController {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var analyzer: QrCodeAnalyzer? = null
    private var analysisExecutor: DroppingExecutor? = null
    private var onQrCodeScanned: ((String) -> Unit)? = null
    private var onCameraPermissionMissing: (() -> Unit)? = null
    private var onScannerUnavailable: (() -> Unit)? = null
    private val isBound = AtomicBoolean(false)
    private var bindGeneration = 0L
    private var hasStartedOnce = false
    private var startToReadyTrace: TraceInterval? = null
    private var startToFirstFrameTrace: TraceInterval? = null
    private val config = CameraVariant.current.androidCameraConfig()
    private val scanTelemetry = ScanTelemetry()

    override fun start(
        onQrCodeScanned: (String) -> Unit,
        onCameraPermissionMissing: () -> Unit,
        onScannerUnavailable: () -> Unit
    ): Boolean {
        this.onQrCodeScanned = onQrCodeScanned
        this.onCameraPermissionMissing = onCameraPermissionMissing
        this.onScannerUnavailable = onScannerUnavailable
        if (!isCameraPermissionGranted(context)) {
            reportCameraPermissionMissing()
            return false
        }
        if (isBound.compareAndSet(false, true)) {
            if (hasStartedOnce) RaylTrace.mark(TracePoint.CameraRestart)
            hasStartedOnce = true
            bindGeneration += 1
            scanTelemetry.sessionStarted()
            startToReadyTrace = RaylTrace.begin(TraceSpan.CameraStartToReady)
            startToFirstFrameTrace = RaylTrace.begin(TraceSpan.CameraStartToFirstFrame)
            bindCamera(
                generation = bindGeneration,
                startToReadyTrace = startToReadyTrace,
                startToFirstFrameTrace = startToFirstFrameTrace
            )
        }
        return true
    }

    override fun stop() {
        val stopTrace = RaylTrace.begin(TraceSpan.CameraStop)
        try {
            bindGeneration += 1
            startToReadyTrace?.end()
            startToReadyTrace = null
            startToFirstFrameTrace?.end()
            startToFirstFrameTrace = null
            cameraProvider?.unbindAll()
            imageAnalysis?.clearAnalyzer()
            imageAnalysis = null
            analyzer?.close()
            analyzer = null
            analysisExecutor?.shutdownAfterDelay()
            analysisExecutor = null
            cameraProvider = null
            onQrCodeScanned = null
            onCameraPermissionMissing = null
            onScannerUnavailable = null
            isBound.set(false)
        } finally {
            stopTrace?.end()
        }
    }

    private fun bindCamera(
        generation: Long,
        startToReadyTrace: TraceInterval?,
        startToFirstFrameTrace: TraceInterval?
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                try {
                    if (!isCurrentGeneration(generation)) return@addListener
                    val provider = cameraProviderFuture.get()
                    if (!isCurrentGeneration(generation)) return@addListener
                    cameraProvider = provider

                    if (!isCurrentGeneration(generation)) return@addListener
                    val analysisExecutor =
                        analysisExecutor ?: DroppingExecutor().also {
                            analysisExecutor = it
                        }
                    val mainExecutor = ContextCompat.getMainExecutor(context)
                    val qrPresenceGate = QrPresenceGate()

                    if (!isCurrentGeneration(generation)) return@addListener
                    val analyzer = analyzer ?: QrCodeAnalyzer(
                        barcodeScanner = newBarcodeScanner(),
                        onQrCodeObserved = { value ->
                            qrPresenceGate.observe(value)?.let { emittedValue ->
                                scanTelemetry.qrDetected(TelemetryScanDecoder.MlKit)
                                mainExecutor.execute {
                                    if (isCurrentGeneration(generation)) {
                                        onQrCodeScanned?.invoke(emittedValue)
                                    }
                                }
                            }
                        },
                        onFirstFrame = {
                            if (isCurrentGeneration(generation)) {
                                startToFirstFrameTrace?.end()
                                scanTelemetry.firstFrame()
                            }
                        },
                        onFrameAnalyzed = scanTelemetry::frameAnalyzed
                    ).also {
                        analyzer = it
                    }

                    if (!isCurrentGeneration(generation)) return@addListener
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        config.analysisResolution,
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER
                                    )
                                )
                                .build()
                        )
                        .build()
                        .also { imageAnalysis ->
                            imageAnalysis.setAnalyzer(analysisExecutor, analyzer)
                        }

                    imageAnalysis = analysis

                    if (!isCurrentGeneration(generation)) return@addListener

                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        analysis
                    )
                    camera.cameraInfo.zoomState.value?.minZoomRatio?.let {
                        camera.cameraControl.setZoomRatio(it)
                    }
                    startToReadyTrace?.end()
                    scanTelemetry.ready()
                } catch (failure: Throwable) {
                    if (!isCurrentGeneration(generation)) return@addListener
                    if (
                        !isCameraPermissionGranted(context) ||
                        failure.hasSecurityExceptionCause()
                    ) {
                        Log.w(TAG, "Camera permission missing while binding CameraX", failure)
                        reportCameraPermissionMissing()
                    } else {
                        Log.e(TAG, "Failed to bind CameraX use cases", failure)
                        reportScannerUnavailable()
                    }
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    private fun isCurrentGeneration(generation: Long): Boolean =
        isBound.get() && bindGeneration == generation

    private fun reportCameraPermissionMissing() {
        val callback = onCameraPermissionMissing
        stop()
        callback?.invoke()
    }

    private fun reportScannerUnavailable() {
        val callback = onScannerUnavailable
        stop()
        callback?.invoke()
    }

    private fun newBarcodeScanner(): BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )
}

/** The scanner settings a [CameraVariant] stands for on Android. */
internal class AndroidCameraConfig(val analysisResolution: Size)

internal fun CameraVariant.androidCameraConfig(): AndroidCameraConfig = when (this) {
    // ML Kit recommends around 1920x1080, which is 16:9. Nothing is shown to the user, so the
    // most common native sensor aspect ratio, 4:3, is preferred.
    CameraVariant.Control -> AndroidCameraConfig(analysisResolution = Size(1920, 1440))
}

private class DroppingExecutor : Executor {
    private val delegate: ExecutorService = Executors.newSingleThreadExecutor()
    private val shutdownHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var accepting = true

    override fun execute(command: Runnable) {
        if (!accepting) return
        try {
            delegate.execute {
                if (accepting) {
                    command.run()
                }
            }
        } catch (error: RejectedExecutionException) {
            Log.d(TAG, "Dropping scanner callback after executor shutdown", error)
        }
    }

    fun shutdownAfterDelay() {
        shutdownHandler.postDelayed(
            {
                accepting = false
                delegate.shutdown()
            },
            ANALYSIS_EXECUTOR_SHUTDOWN_DELAY_MILLIS
        )
    }
}

private class QrCodeAnalyzer(
    private val barcodeScanner: BarcodeScanner,
    private val onQrCodeObserved: (String?) -> Unit,
    private val onFirstFrame: () -> Unit,
    private val onFrameAnalyzed: (durationMs: Long, width: Int, height: Int) -> Unit
) : ImageAnalysis.Analyzer {
    private val firstFrameReported = AtomicBoolean(false)

    fun close() {
        barcodeScanner.close()
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val imageClosed = AtomicBoolean(false)
        var analysisTrace: TraceInterval? = null
        fun closeImage() {
            if (imageClosed.compareAndSet(false, true)) {
                image.close()
            }
        }

        try {
            val mediaImage = image.image
            if (mediaImage == null) {
                closeImage()
                return
            }

            if (firstFrameReported.compareAndSet(false, true)) {
                onFirstFrame()
            }
            analysisTrace = RaylTrace.begin(TraceSpan.CameraFrameAnalysis)
            val analysisStartedMs = SystemClock.elapsedRealtime()
            val input = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
            barcodeScanner.process(input)
                .addOnCompleteListener(DirectExecutor) { task ->
                    try {
                        onFrameAnalyzed(
                            SystemClock.elapsedRealtime() - analysisStartedMs,
                            image.width,
                            image.height
                        )
                        if (!task.isSuccessful) {
                            Log.e(TAG, "Barcode scanning failed", task.exception)
                            return@addOnCompleteListener
                        }
                        val rotation = image.imageInfo.rotationDegrees
                        val frameWidth = if (rotation == 90 || rotation == 270) {
                            image.height.toFloat()
                        } else {
                            image.width.toFloat()
                        }
                        val frameHeight = if (rotation == 90 || rotation == 270) {
                            image.width.toFloat()
                        } else {
                            image.height.toFloat()
                        }
                        val value = selectPreferredBarcodeValue(
                            barcodes = task.result.orEmpty(),
                            frameWidth = frameWidth,
                            frameHeight = frameHeight
                        )
                        onQrCodeObserved(value)
                    } finally {
                        analysisTrace?.end()
                        closeImage()
                    }
                }
        } catch (failure: Throwable) {
            analysisTrace?.end()
            closeImage()
            Log.e(TAG, "Unexpected failure while analyzing image", failure)
        }
    }

    private object DirectExecutor : Executor {
        override fun execute(command: Runnable) {
            command.run()
        }
    }
}

private fun selectPreferredBarcodeValue(
    barcodes: List<Barcode>,
    frameWidth: Float,
    frameHeight: Float
): String? {
    if (barcodes.isEmpty()) return null
    if (barcodes.size == 1) {
        return barcodes.first().rawValue?.takeIf { it.isNotBlank() }
    }

    val candidates = ArrayList<QrDetectionCandidate>(barcodes.size)
    var firstValue: String? = null
    for (barcode in barcodes) {
        val value = barcode.rawValue?.takeIf { it.isNotBlank() } ?: continue
        if (firstValue == null) firstValue = value
        val bounds = barcode.boundingBox ?: continue
        candidates.add(
            QrDetectionCandidate(
                value = value,
                left = bounds.left.toFloat(),
                top = bounds.top.toFloat(),
                right = bounds.right.toFloat(),
                bottom = bounds.bottom.toFloat()
            )
        )
    }

    if (candidates.isEmpty()) return firstValue
    return pickPreferredQrValue(candidates, frameWidth, frameHeight)
}

private fun isCameraPermissionGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

private fun Throwable.hasSecurityExceptionCause(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is SecurityException) return true
        current = current.cause
    }
    return false
}
