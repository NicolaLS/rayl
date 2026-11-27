package xyz.lilsus.raylsuite.core.camera

import android.Manifest
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.SessionConfig
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import xyz.lilsus.raylsuite.core.telemetry.RaylTrace
import xyz.lilsus.raylsuite.core.telemetry.TraceInterval
import xyz.lilsus.raylsuite.core.telemetry.TracePoint
import xyz.lilsus.raylsuite.core.telemetry.TraceSpan

private const val TAG = "QrScanner"
private const val ANALYSIS_EXECUTOR_SHUTDOWN_DELAY_MILLIS = 2_000L

/** How often a running scanner is checked for frames. */
private const val FRAME_WATCH_INTERVAL_MILLIS = 1_000L

/**
 * Without a frame for this long the camera counts as stalled. A slow phone takes a second or two
 * to open its camera.
 */
private const val FRAME_STALL_MILLIS = 4_000L

/** A camera that stalls again right after being bound anew is reported as unavailable. */
private const val MAX_STALL_REBINDS = 1

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
    private var onQrCodesScanned: ((List<String>) -> Unit)? = null
    private var onCameraPermissionMissing: (() -> Unit)? = null
    private var onScannerUnavailable: (() -> Unit)? = null
    private val isBound = AtomicBoolean(false)
    private var bindGeneration = 0L
    private var hasStartedOnce = false
    private var startToReadyTrace: TraceInterval? = null
    private var startToFirstFrameTrace: TraceInterval? = null
    private val config = CameraVariant.current.androidCameraConfig()
    private val scanTelemetry = ScanTelemetry()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** When the camera last delivered a frame; written on the analysis thread. */
    @Volatile
    private var lastFrameAtMs = 0L

    /** How often in a row the camera was bound anew because it delivered no frames. */
    private var stallRebinds = 0

    private val decoder = qrFrameDecoder()

    init {
        // The reader loads while the camera opens, not on the first frame.
        decoder.warmUp()
    }

    override fun start(
        onQrCodesScanned: (List<String>) -> Unit,
        onCameraPermissionMissing: () -> Unit,
        onScannerUnavailable: () -> Unit
    ): Boolean {
        this.onQrCodesScanned = onQrCodesScanned
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
            stallRebinds = 0
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
            analyzer = null
            analysisExecutor?.shutdownAfterDelay()
            analysisExecutor = null
            cameraProvider = null
            onQrCodesScanned = null
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
                        decoder = decoder,
                        onQrCodesObserved = { values ->
                            val arrived = qrPresenceGate.observe(values)
                            if (arrived.isNotEmpty()) {
                                scanTelemetry.qrDetected()
                                mainExecutor.execute {
                                    if (isCurrentGeneration(generation)) {
                                        onQrCodesScanned?.invoke(arrived)
                                    }
                                }
                            }
                        },
                        onFirstFrame = {
                            lastFrameAtMs = SystemClock.elapsedRealtime()
                            if (isCurrentGeneration(generation)) {
                                startToFirstFrameTrace?.end()
                                scanTelemetry.firstFrame()
                            }
                        },
                        onFrameAnalyzed = { durationMs, width, height ->
                            lastFrameAtMs = SystemClock.elapsedRealtime()
                            scanTelemetry.frameAnalyzed(durationMs, width, height)
                        }
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
                    val camera = bindAnalysis(
                        provider = provider,
                        analysis = analysis,
                        frameRate = config.fixedFrameRateUpTo?.let { maxFps ->
                            shortExposureFrameRate(provider, analysis, maxFps)
                        }
                    )
                    camera.cameraInfo.zoomState.value?.minZoomRatio?.let {
                        camera.cameraControl.setZoomRatio(it)
                    }
                    startToReadyTrace?.end()
                    scanTelemetry.ready()
                    lastFrameAtMs = SystemClock.elapsedRealtime()
                    watchFrames(generation)
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

    /**
     * Nothing on screen shows this scanner's picture, so a camera that stopped delivering frames
     * looks exactly like one with no code in view. While the scanner bound as [generation] runs,
     * a camera without frames is bound anew, and one that stays without them is reported as
     * unavailable so the screen can say so.
     */
    private fun watchFrames(generation: Long) {
        mainHandler.postDelayed(
            {
                if (!isCurrentGeneration(generation)) return@postDelayed
                val now = SystemClock.elapsedRealtime()
                val screenStarted =
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                when {
                    // CameraX closes the camera with the screen; the wait starts over with it.
                    !screenStarted -> lastFrameAtMs = now

                    now - lastFrameAtMs < FRAME_STALL_MILLIS -> stallRebinds = 0

                    stallRebinds >= MAX_STALL_REBINDS -> {
                        Log.e(TAG, "Camera delivers no frames after being bound anew")
                        reportScannerUnavailable()
                        return@postDelayed
                    }

                    else -> {
                        Log.w(TAG, "Camera delivers no frames; binding it anew")
                        stallRebinds += 1
                        rebind()
                        return@postDelayed
                    }
                }
                watchFrames(generation)
            },
            FRAME_WATCH_INTERVAL_MILLIS
        )
    }

    /** Closes the camera and binds it again for the running scanner. */
    private fun rebind() {
        bindGeneration += 1
        cameraProvider?.unbindAll()
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        analyzer = null
        bindCamera(
            generation = bindGeneration,
            startToReadyTrace = null,
            startToFirstFrameTrace = null
        )
    }

    /**
     * The frame rate that keeps exposures shortest for [analysis], of those the back camera
     * guarantees for it; null when it names none. See [pickShortExposureFrameRate].
     */
    private fun shortExposureFrameRate(
        provider: ProcessCameraProvider,
        analysis: ImageAnalysis,
        maxFps: Int
    ): Range<Int>? = try {
        val supported = provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
            .getSupportedFrameRateRanges(SessionConfig(analysis))
        pickShortExposureFrameRate(supported.map { it.lower..it.upper }, maxFps)
            ?.let { Range(it.first, it.last) }
    } catch (failure: RuntimeException) {
        Log.w(TAG, "Could not read the camera's frame rates", failure)
        null
    }

    /** Binds [analysis] at [frameRate], or at the camera's own rate when it refuses that one. */
    private fun bindAnalysis(
        provider: ProcessCameraProvider,
        analysis: ImageAnalysis,
        frameRate: Range<Int>?
    ): Camera {
        if (frameRate != null) {
            try {
                val camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    SessionConfig.Builder(listOf(analysis)).setFrameRateRange(frameRate).build()
                )
                Log.d(TAG, "Scanning at $frameRate frames per second")
                return camera
            } catch (failure: RuntimeException) {
                // A scanner with the camera's default exposure is better than none.
                Log.w(TAG, "Camera refused $frameRate frames per second", failure)
                provider.unbindAll()
            }
        }
        return provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            analysis
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
}

/**
 * The scanner settings a [CameraVariant] stands for on Android. With [fixedFrameRateUpTo] the
 * camera runs at the highest frame rate up to it that it can hold in any light, which is what
 * limits how long a frame is exposed (see [pickShortExposureFrameRate]); null leaves the camera
 * free to slow down in dim light.
 */
internal class AndroidCameraConfig(val analysisResolution: Size, val fixedFrameRateUpTo: Int?)

internal fun CameraVariant.androidCameraConfig(): AndroidCameraConfig = when (this) {
    // Around 1920x1080 is recommended for dense QR codes. Nothing is shown to the user, so the
    // most common native sensor aspect ratio, 4:3, is preferred.
    CameraVariant.Control -> AndroidCameraConfig(
        analysisResolution = Size(1920, 1440),
        fixedFrameRateUpTo = 60
    )
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
    private val decoder: QrFrameDecoder,
    private val onQrCodesObserved: (List<String>) -> Unit,
    private val onFirstFrame: () -> Unit,
    private val onFrameAnalyzed: (durationMs: Long, width: Int, height: Int) -> Unit
) : ImageAnalysis.Analyzer {
    private val firstFrameReported = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        val imageClosed = AtomicBoolean(false)
        var analysisTrace: TraceInterval? = null
        fun closeImage() {
            if (imageClosed.compareAndSet(false, true)) {
                image.close()
            }
        }

        try {
            if (firstFrameReported.compareAndSet(false, true)) {
                onFirstFrame()
            }
            analysisTrace = RaylTrace.begin(TraceSpan.CameraFrameAnalysis)
            val analysisStartedMs = SystemClock.elapsedRealtime()
            decoder.decode(image) { values ->
                try {
                    onFrameAnalyzed(
                        SystemClock.elapsedRealtime() - analysisStartedMs,
                        image.width,
                        image.height
                    )
                    if (values != null) {
                        onQrCodesObserved(values)
                    }
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
