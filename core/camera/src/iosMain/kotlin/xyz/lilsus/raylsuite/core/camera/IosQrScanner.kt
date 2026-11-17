@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class
)

package xyz.lilsus.raylsuite.core.camera

import kotlin.concurrent.AtomicLong
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceDiscoverySession
import platform.AVFoundation.AVCaptureDeviceFormat
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDeviceMeta
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInDualWideCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInTripleCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInUltraWideCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureExposureModeContinuousAutoExposure
import platform.AVFoundation.AVCaptureFocusModeContinuousAutoFocus
import platform.AVFoundation.AVCaptureInput
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetHigh
import platform.AVFoundation.AVCaptureSessionPresetInputPriority
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.AVFoundation.AVFrameRateRange
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.ISO
import platform.AVFoundation.activeMaxExposureDuration
import platform.AVFoundation.adjustingFocus
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.deviceType
import platform.AVFoundation.exposureDuration
import platform.AVFoundation.exposureMode
import platform.AVFoundation.exposureTargetBias
import platform.AVFoundation.exposureTargetOffset
import platform.AVFoundation.focusMode
import platform.AVFoundation.isExposureModeSupported
import platform.AVFoundation.isFocusModeSupported
import platform.AVFoundation.lensPosition
import platform.AVFoundation.maxAvailableVideoZoomFactor
import platform.AVFoundation.maxExposureTargetBias
import platform.AVFoundation.minAvailableVideoZoomFactor
import platform.AVFoundation.minExposureTargetBias
import platform.AVFoundation.setExposureTargetBias
import platform.AVFoundation.videoZoomFactor
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMTimeCompare
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMVideoFormatDescriptionGetDimensions
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.Foundation.NSError
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.Vision.VNBarcodeObservation
import platform.Vision.VNBarcodeSymbologyQR
import platform.Vision.VNDetectBarcodesRequest
import platform.Vision.VNSequenceRequestHandler
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_MSEC
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_t
import platform.darwin.dispatch_time
import xyz.lilsus.raylsuite.core.telemetry.RaylTrace
import xyz.lilsus.raylsuite.core.telemetry.TracePoint
import xyz.lilsus.raylsuite.core.telemetry.TraceSpan

/** Creates the iOS scanner for a native SwiftUI-owned screen lifecycle. */
fun createNativeQrScannerController(): QrScannerController =
    TestQrInput.scannerOrNull(::afterOnMainQueue) ?: IosQrScannerController()

private fun afterOnMainQueue(delayMillis: Long, block: () -> Unit): TestQrInput.Cancel {
    var cancelled = false
    dispatch_after(
        dispatch_time(DISPATCH_TIME_NOW, delayMillis * NSEC_PER_MSEC.toLong()),
        dispatch_get_main_queue()
    ) {
        if (!cancelled) block()
    }
    return TestQrInput.Cancel { cancelled = true }
}

private class IosQrScannerController : QrScannerController {
    private val session = AVCaptureSession()
    private val sessionQueue: dispatch_queue_t = dispatch_queue_create(
        "xyz.lilsus.raylsuite.qr.session",
        null
    )

    // Vision decodes on its own queue, so a frame being read never holds up starting, stopping
    // or adjusting the camera; it reports to the session queue with the time it decoded at.
    private val visionQueue: dispatch_queue_t = dispatch_queue_create(
        "xyz.lilsus.raylsuite.qr.vision",
        null
    )
    private val visionDelegate = VisionDelegate(
        isActive = { started },
        onFrame = {
            lastFrameAtMs.value = RaylTrace.nowMs()
            scanTelemetry.firstFrame()
        },
        onFrameAnalyzed = { durationMs, width, height ->
            scanTelemetry.frameAnalyzed(durationMs, width, height)
            diagnostics.visionAnalyzed(durationMs)
        },
        onValues = { values, atMs ->
            diagnostics.visionResult(read = values.isNotEmpty(), nowMs = atMs, device = device)
            dispatch_async(sessionQueue) {
                observeQrCode(values, atMs)
            }
        }
    )

    /** When the camera last delivered a frame to Vision, or was last started. */
    private val lastFrameAtMs = AtomicLong(0L)

    /** How often in a row the camera was restarted for delivering no frames; session queue only. */
    private var stallRestarts = 0

    private var onQrCodesScanned: ((List<String>) -> Unit)? = null
    private var onScannerUnavailable: (() -> Unit)? = null
    private var lifecycleObserver: NSObjectProtocol? = null
    private var started = false
    private var configured = false
    private val qrPresenceGate = QrPresenceGate()
    private var hasStartedOnce = false
    private var generation = 0L
    private val config = CameraVariant.current.iosCameraConfig()
    private val scanTelemetry = ScanTelemetry()
    private val diagnostics = ScanDiagnostics()

    /** The camera in use, whose exposure is read and set while it runs. */
    private var device: AVCaptureDevice? = null

    /** When the running scanner was started; session queue only. */
    private var startedAtMs = 0L

    /**
     * The exposure setting in use; session queue only. It outlives a restart: the room is the
     * same one a few seconds later.
     */
    private var exposureSetting = config.exposure?.start

    override fun start(
        onQrCodesScanned: (List<String>) -> Unit,
        onCameraPermissionMissing: () -> Unit,
        onScannerUnavailable: () -> Unit
    ): Boolean {
        if (!isNativeCameraAuthorized()) {
            onCameraPermissionMissing()
            return false
        }
        this.onQrCodesScanned = onQrCodesScanned
        this.onScannerUnavailable = onScannerUnavailable
        ensureLifecycleObserver()
        if (hasStartedOnce) RaylTrace.mark(TracePoint.CameraRestart)
        hasStartedOnce = true
        generation += 1
        val currentGeneration = generation
        scanTelemetry.sessionStarted()
        val startTrace = RaylTrace.begin(TraceSpan.CameraStartToReady)
        val startCalledMs = RaylTrace.nowMs()
        dispatch_async(sessionQueue) {
            try {
                if (generation != currentGeneration) return@dispatch_async
                startedAtMs = startCalledMs
                qrPresenceGate.reset()
                started = true
                stallRestarts = 0
                ensureSessionRunning()
                watchCamera(currentGeneration)
            } finally {
                startTrace?.end()
            }
        }
        return true
    }

    override fun stop() {
        generation += 1
        removeLifecycleObserver()
        onQrCodesScanned = null
        onScannerUnavailable = null
        dispatch_async(sessionQueue) {
            val stopTrace = RaylTrace.begin(TraceSpan.CameraStop)
            try {
                started = false
                if (session.running) session.stopRunning()
                teardownSession()
                configured = false
            } finally {
                stopTrace?.end()
            }
        }
    }

    private fun ensureSessionRunning() {
        if (!configured) configured = configureSession()
        if (!configured) {
            reportScannerUnavailable()
            return
        }
        if (!session.running && started) {
            // Blocks until the session runs. Its first frame comes some hundred milliseconds
            // later, and the wait for one starts here.
            session.startRunning()
            lastFrameAtMs.value = RaylTrace.nowMs()
        }
        // Vision reports the first frame once it is given one.
        if (session.running && started) scanTelemetry.ready()
    }

    /**
     * On the session queue, every [CAMERA_CHECK_MS] while the scanner started as [forGeneration]
     * runs: restarts a camera that delivers no frames and fits the exposure to the light.
     */
    private fun watchCamera(forGeneration: Long) {
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, CAMERA_CHECK_MS * NSEC_PER_MSEC.toLong()),
            sessionQueue
        ) {
            if (!started || generation != forGeneration) return@dispatch_after
            if (!recoverStalledCamera()) return@dispatch_after
            fitExposureToLight()
            watchCamera(forGeneration)
        }
    }

    /**
     * Nothing on screen shows this scanner's picture, so a camera that stopped delivering frames,
     * after an interruption or an error of the capture session, looks exactly like one with no
     * code in view. Such a camera is restarted, and one that stays without frames is reported as
     * unavailable so the screen can say so. False when the scanner was given up on.
     */
    private fun recoverStalledCamera(): Boolean {
        if (RaylTrace.nowMs() - lastFrameAtMs.value < FRAME_STALL_MS) {
            stallRestarts = 0
            return true
        }
        if (stallRestarts >= MAX_STALL_RESTARTS) {
            RaylTrace.debug("scanner: still no frames after a restart, giving up")
            reportScannerUnavailable()
            return false
        }
        RaylTrace.debug("scanner: no frames, restarting the camera")
        stallRestarts += 1
        if (session.running) session.stopRunning()
        teardownSession()
        configured = false
        ensureSessionRunning()
        return started
    }

    /** Keeps the exposure setting at what the [ExposurePolicy] wants for the light reported. */
    private fun fitExposureToLight() {
        val policy = config.exposure ?: return
        val device = device ?: return
        val current = exposureSetting ?: return
        if (!session.running) return
        val wanted = policy.settingFor(
            current = current,
            exposureMs = CMTimeGetSeconds(device.exposureDuration) * 1_000,
            iso = device.ISO.toDouble(),
            evFromTarget = device.exposureTargetOffset.toDouble()
        )
        if (wanted !== current) setExposure(device, wanted)?.let { exposureSetting = it }
    }

    private fun configureSession(): Boolean {
        val configurationTrace = RaylTrace.begin(TraceSpan.CameraConfigureSession)
        try {
            val selection = selectScannerCamera(config) ?: return false
            val device = selection.device
            this.device = device
            val input = createDeviceInput(device) ?: return false

            session.beginConfiguration()
            return try {
                removeAllInputsAndOutputs()
                val appliedFormat = applyPreferredCaptureFormat(
                    device = device,
                    target = selection.formatTarget,
                    captureFps = config.captureFps
                )
                appliedFormat?.let {
                    scanTelemetry.frameSize(it.width, it.height)
                    RaylTrace.debug("scanner: capture format ${it.width}x${it.height}")
                }
                val sessionPreset = if (appliedFormat != null) {
                    AVCaptureSessionPresetInputPriority
                } else {
                    AVCaptureSessionPresetHigh
                }
                if (session.canSetSessionPreset(sessionPreset)) {
                    session.sessionPreset = sessionPreset
                }
                if (!session.canAddInput(input)) return false
                session.addInput(input)
                if (!addVisionOutput()) return false
                exposureSetting = applyFixedCameraControls(device, exposureSetting)
                true
            } finally {
                session.commitConfiguration()
            }
        } finally {
            configurationTrace?.end()
        }
    }

    private fun createDeviceInput(device: AVCaptureDevice): AVCaptureDeviceInput? = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val input = AVCaptureDeviceInput.deviceInputWithDevice(device, error.ptr)
        error.value?.let { failure ->
            NSLog("AVCaptureDeviceInput error: ${failure.localizedDescription}")
            return@memScoped null
        }
        input
    }

    private fun teardownSession() {
        session.beginConfiguration()
        try {
            removeAllInputsAndOutputs()
        } finally {
            session.commitConfiguration()
        }
    }

    private fun removeAllInputsAndOutputs() {
        @Suppress("UNCHECKED_CAST")
        val inputs = session.inputs as? List<AVCaptureInput> ?: emptyList()
        inputs.forEach(session::removeInput)

        @Suppress("UNCHECKED_CAST")
        val outputs = session.outputs as? List<AVCaptureOutput> ?: emptyList()
        outputs.forEach(session::removeOutput)
    }

    /** The session's output, feeding the camera's frames to Vision. */
    private fun addVisionOutput(): Boolean {
        val videoOutput = AVCaptureVideoDataOutput()
        if (!session.canAddOutput(videoOutput)) return false
        // A frame that arrives while Vision is busy is dropped, never queued.
        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.setSampleBufferDelegate(visionDelegate, visionQueue)
        session.addOutput(videoOutput)
        return true
    }

    /** On the session queue. */
    private fun observeQrCode(values: List<String>, atMs: Long) {
        // Vision finishes a frame it was reading when the scanner stopped.
        if (!started) return
        val arrived = qrPresenceGate.observe(values)
        if (arrived.isEmpty()) return
        scanTelemetry.qrDetected()
        if (RaylTrace.debugLog) {
            RaylTrace.debug(
                "scanner: read a code ${atMs - startedAtMs} ms after start; " +
                    device?.diagnosticState()
            )
        }
        val callback = onQrCodesScanned ?: return
        val currentGeneration = generation
        dispatch_async(dispatch_get_main_queue()) {
            if (started && generation == currentGeneration) callback(arrived)
        }
    }

    private fun reportScannerUnavailable() {
        val callback = onScannerUnavailable
        val currentGeneration = generation
        started = false
        if (session.running) session.stopRunning()
        teardownSession()
        configured = false
        dispatch_async(dispatch_get_main_queue()) {
            removeLifecycleObserver()
            if (generation == currentGeneration) callback?.invoke()
        }
    }

    private fun ensureLifecycleObserver() {
        if (lifecycleObserver != null) return
        lifecycleObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { _ ->
            dispatch_async(sessionQueue) {
                if (started) ensureSessionRunning()
            }
        }
    }

    private fun removeLifecycleObserver() {
        lifecycleObserver?.let(NSNotificationCenter.defaultCenter::removeObserver)
        lifecycleObserver = null
    }
}

/**
 * Debug builds: about once a second, in how many frames Vision decoded a code and what the
 * camera's focus and exposure were, under `RaylTiming`. It tells a code in view that is not being
 * read from one that is, which no timing does. Vision queue only.
 */
private class ScanDiagnostics {
    private var frames = 0
    private var reads = 0
    private var analysisMs = 0L
    private var durationOfLastFrameMs = 0L
    private var windowStartedMs = 0L

    fun visionAnalyzed(durationMs: Long) {
        analysisMs += durationMs
        durationOfLastFrameMs = durationMs
    }

    fun visionResult(read: Boolean, nowMs: Long, device: AVCaptureDevice?) {
        if (!RaylTrace.debugLog) return
        if (nowMs - windowStartedMs > 2 * WINDOW_MS) {
            // The scanner was off in between: these frames start a new window.
            frames = 0
            reads = 0
            analysisMs = durationOfLastFrameMs
            windowStartedMs = nowMs
        }
        frames += 1
        if (read) reads += 1
        if (nowMs - windowStartedMs < WINDOW_MS) return
        RaylTrace.debug(
            "scanner: read $reads of $frames frames (${analysisMs / frames} ms each); " +
                device?.diagnosticState()
        )
        frames = 0
        reads = 0
        analysisMs = 0L
        windowStartedMs = nowMs
    }

    private companion object {
        const val WINDOW_MS = 1_000L
    }
}

private fun AVCaptureDevice.diagnosticState(): String {
    val exposureMs = (CMTimeGetSeconds(exposureDuration) * 1_000).rounded(decimals = 1)
    val limitMs = (CMTimeGetSeconds(activeMaxExposureDuration) * 1_000).rounded(decimals = 1)
    return "lens ${lensPosition.toDouble().rounded(decimals = 2)}" +
        "${if (adjustingFocus) " (focusing)" else ""}, " +
        "exposure $exposureMs ms (limit $limitMs) ISO ${ISO.toDouble().rounded(decimals = 0)}, " +
        "${exposureTargetOffset.toDouble().rounded(decimals = 1)} EV from target " +
        "(bias ${exposureTargetBias.toDouble().rounded(decimals = 1)})"
}

/**
 * For the debug log. The camera reports a value it does not have just now as not-a-number,
 * which cannot be rounded.
 */
private fun Double.rounded(decimals: Int): String {
    if (!isFinite()) return "?"
    val scale = 10.0.pow(decimals)
    val rounded = round(this * scale) / scale
    return if (decimals == 0) rounded.toLong().toString() else rounded.toString()
}

/**
 * Reads QR codes from the camera's frames with Apple's Vision framework, one frame at a time.
 * It reports every frame's result, also "no code".
 */
private class VisionDelegate(
    private val isActive: () -> Boolean,
    private val onFrame: () -> Unit,
    private val onFrameAnalyzed: (durationMs: Long, width: Int, height: Int) -> Unit,
    private val onValues: (values: List<String>, atMs: Long) -> Unit
) : NSObject(),
    AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
    private val request = VNDetectBarcodesRequest().apply {
        symbologies = listOf(VNBarcodeSymbologyQR)
    }

    // One handler for every frame. A handler made per frame keeps its frame's buffer alive until
    // Kotlin's garbage collector gets to it; the camera has only a few buffers, so it stopped
    // delivering frames after about a dozen and Vision went blind until the next collection.
    private val handler = VNSequenceRequestHandler()

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputSampleBuffer: CMSampleBufferRef?,
        fromConnection: AVCaptureConnection
    ) {
        if (!isActive()) return
        onFrame()
        val pixelBuffer = CMSampleBufferGetImageBuffer(didOutputSampleBuffer) ?: return
        val startedMs = RaylTrace.nowMs()
        val performed = autoreleasepool {
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                handler.performRequests(listOf(request), pixelBuffer, error.ptr)
            }
        }
        val now = RaylTrace.nowMs()
        onFrameAnalyzed(
            now - startedMs,
            CVPixelBufferGetWidth(pixelBuffer).toInt(),
            CVPixelBufferGetHeight(pixelBuffer).toInt()
        )
        if (!performed) return
        val candidates = request.results.orEmpty().mapNotNull { result ->
            val code = result as? VNBarcodeObservation ?: return@mapNotNull null
            val value = code.payloadStringValue?.trim()?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            // Vision's box is normalised with its origin at the bottom left.
            code.boundingBox.useContents {
                val left = origin.x.toFloat()
                val top = 1f - (origin.y + size.height).toFloat()
                QrDetectionCandidate(
                    value = value,
                    left = left,
                    top = top,
                    right = left + size.width.toFloat(),
                    bottom = top + size.height.toFloat()
                )
            }
        }
        onValues(rankQrValues(candidates, frameWidth = 1f, frameHeight = 1f), now)
    }
}

private data class ScannerCameraSelection(
    val device: AVCaptureDevice,
    val formatTarget: CaptureFormatTarget
)

internal data class CaptureFormatTarget(
    val targetAspectRatio: Double,
    val preferredPixels: Int,
    val minimumPixels: Int,
    val maximumPixels: Int
)

private data class CaptureFormatCandidate(
    val format: AVCaptureDeviceFormat,
    val width: Int,
    val height: Int,
    val maximumFrameRate: Double
) {
    val pixelCount: Int
        get() = width * height
    val aspectRatio: Double
        get() = width.toDouble() / height.toDouble()
}

private fun selectScannerCamera(config: IosCameraConfig): ScannerCameraSelection? {
    val discovery = AVCaptureDeviceDiscoverySession.discoverySessionWithDeviceTypes(
        deviceTypes = SCANNER_CAMERA_DEVICE_TYPES,
        mediaType = AVMediaTypeVideo,
        position = AVCaptureDevicePositionBack
    )

    @Suppress("UNCHECKED_CAST")
    val devices = discovery.devices as? List<AVCaptureDevice> ?: emptyList()
    val virtualWide = devices.firstOrNull {
        it.deviceType == AVCaptureDeviceTypeBuiltInDualWideCamera
    } ?: devices.firstOrNull {
        it.deviceType == AVCaptureDeviceTypeBuiltInTripleCamera
    }
    val ultraWide = devices.firstOrNull {
        it.deviceType == AVCaptureDeviceTypeBuiltInUltraWideCamera
    }
    val wide = devices.firstOrNull {
        it.deviceType == AVCaptureDeviceTypeBuiltInWideAngleCamera
    }

    return when {
        virtualWide != null -> ScannerCameraSelection(virtualWide, config.fourThreeTarget)

        ultraWide != null -> ScannerCameraSelection(ultraWide, config.fourThreeTarget)

        wide != null -> ScannerCameraSelection(wide, config.wideFallbackTarget)

        else -> AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)?.let {
            ScannerCameraSelection(it, config.wideFallbackTarget)
        }
    }
}

/** The format now active on [device], or null when none of its formats could be applied. */
private fun applyPreferredCaptureFormat(
    device: AVCaptureDevice,
    target: CaptureFormatTarget,
    captureFps: Double
): CaptureFormatCandidate? {
    @Suppress("UNCHECKED_CAST")
    val formats = device.formats as? List<AVCaptureDeviceFormat> ?: return null
    val candidates = formats.mapNotNull { format ->
        val description = format.formatDescription ?: return@mapNotNull null
        val dimensions = CMVideoFormatDescriptionGetDimensions(description)
        val width = dimensions.useContents { width }
        val height = dimensions.useContents { height }
        if (width <= 0 || height <= 0) return@mapNotNull null
        @Suppress("UNCHECKED_CAST")
        val frameRateRanges = format.videoSupportedFrameRateRanges as? List<AVFrameRateRange>
            ?: return@mapNotNull null
        val maximumFrameRate = frameRateRanges.maxOfOrNull { it.maxFrameRate }
            ?: return@mapNotNull null
        if (maximumFrameRate < captureFps) return@mapNotNull null
        CaptureFormatCandidate(format, width, height, maximumFrameRate)
    }
    val comparator = compareBy<CaptureFormatCandidate> {
        abs(it.aspectRatio - target.targetAspectRatio)
    }.thenBy {
        abs(it.pixelCount - target.preferredPixels)
    }.thenByDescending(CaptureFormatCandidate::pixelCount)
        .thenByDescending(CaptureFormatCandidate::maximumFrameRate)
    val aspectMatches: (CaptureFormatCandidate) -> Boolean = {
        abs(it.aspectRatio - target.targetAspectRatio) <= MAXIMUM_ASPECT_DELTA
    }
    val ranked = linkedSetOf<CaptureFormatCandidate>()
    ranked += candidates.filter {
        it.pixelCount in target.minimumPixels..target.maximumPixels && aspectMatches(it)
    }.sortedWith(comparator)
    ranked += candidates.filter {
        it.pixelCount >= target.minimumPixels && aspectMatches(it)
    }.sortedWith(comparator)
    ranked += candidates.filter(aspectMatches).sortedWith(comparator)

    return ranked.firstOrNull { candidate -> applyCaptureFormat(device, candidate, captureFps) }
}

private fun applyCaptureFormat(
    device: AVCaptureDevice,
    candidate: CaptureFormatCandidate,
    captureFps: Double
): Boolean = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    if (!device.lockForConfiguration(error.ptr)) return@memScoped false
    try {
        val frameDuration = CMTimeMake(
            value = 1,
            timescale = captureFps.toInt()
        )
        device.activeFormat = candidate.format
        device.activeVideoMinFrameDuration = frameDuration
        device.activeVideoMaxFrameDuration = frameDuration
        true
    } finally {
        device.unlockForConfiguration()
    }
}

/**
 * After the capture format is set: a new format puts the exposure limit back to its default.
 * Returns [exposure] as the camera took it.
 */
private fun applyFixedCameraControls(
    device: AVCaptureDevice,
    exposure: ExposurePolicy.Setting?
): ExposurePolicy.Setting? = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    if (!device.lockForConfiguration(error.ptr)) return@memScoped exposure
    try {
        device.videoZoomFactor = 1.0.coerceIn(
            device.minAvailableVideoZoomFactor,
            device.maxAvailableVideoZoomFactor
        )
        if (device.isFocusModeSupported(AVCaptureFocusModeContinuousAutoFocus)) {
            device.focusMode = AVCaptureFocusModeContinuousAutoFocus
        }
        if (device.isExposureModeSupported(AVCaptureExposureModeContinuousAutoExposure)) {
            device.exposureMode = AVCaptureExposureModeContinuousAutoExposure
        }
        exposure?.let { applyExposure(device, it) }
    } finally {
        device.unlockForConfiguration()
    }
}

/** [setting] as the camera took it, or null when the camera could not be configured just now. */
private fun setExposure(
    device: AVCaptureDevice,
    setting: ExposurePolicy.Setting
): ExposurePolicy.Setting? = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    if (!device.lockForConfiguration(error.ptr)) return@memScoped null
    try {
        applyExposure(device, setting)
    } finally {
        device.unlockForConfiguration()
    }
}

/**
 * Tells auto-exposure what an [ExposurePolicy] decided and returns it as the camera took it: a
 * camera aims only so far from its usual brightness. The device must be locked for configuration.
 */
private fun applyExposure(
    device: AVCaptureDevice,
    setting: ExposurePolicy.Setting
): ExposurePolicy.Setting {
    val format = device.activeFormat
    val wanted = CMTimeMake(value = (setting.limitMs * 1_000).toLong(), timescale = 1_000_000)
    // A limit outside what the format can do throws, and so does a bias outside the camera's.
    device.activeMaxExposureDuration = when {
        CMTimeCompare(wanted, format.minExposureDuration) < 0 -> format.minExposureDuration
        CMTimeCompare(wanted, format.maxExposureDuration) > 0 -> format.maxExposureDuration
        else -> wanted
    }
    val biasEv = setting.biasEv.coerceIn(
        device.minExposureTargetBias.toDouble(),
        device.maxExposureTargetBias.toDouble()
    )
    device.setExposureTargetBias(biasEv.toFloat(), completionHandler = null)
    return if (biasEv ==
        setting.biasEv
    ) {
        setting
    } else {
        ExposurePolicy.Setting(setting.limitMs, biasEv)
    }
}

private const val MAXIMUM_ASPECT_DELTA = 0.08

/** How often a running scanner's camera is checked for frames and its exposure for the light. */
private const val CAMERA_CHECK_MS = 250L

/** Without a frame for this long the camera counts as stalled; its first comes within a second. */
private const val FRAME_STALL_MS = 3_000L

/** A camera that stalls again right after a restart is reported as unavailable. */
private const val MAX_STALL_RESTARTS = 1

/**
 * The scanner settings a [CameraVariant] stands for on iOS: the capture rate, the format wanted
 * from a camera with an ultra-wide lens and the one wanted from a wide-angle-only camera.
 * [exposure] keeps frames short and clean rather than bright; null leaves exposure to the
 * camera, which in dim light exposes for a whole frame's time and blurs a moving code.
 */
internal class IosCameraConfig(
    val captureFps: Double,
    val exposure: ExposurePolicy?,
    val fourThreeTarget: CaptureFormatTarget,
    val wideFallbackTarget: CaptureFormatTarget
)

internal fun CameraVariant.iosCameraConfig(): IosCameraConfig = when (this) {
    CameraVariant.Control -> IosCameraConfig(
        captureFps = 30.0,
        // 2 ms at ISO 1000 is about the right exposure for a phone or till screen, which is
        // where codes nearly always are, and a hand-held code moves by less than a module in
        // that time. White paper in ordinary room light still comes out light grey. A printed
        // code in a dim room is given up for it: it comes out too dark to read from afar.
        exposure = ExposurePolicy(shortestMs = 1.0, longestMs = 2.0, isoCeiling = 1_000.0),
        // The ultra-wide lens spreads its frame over about 106 degrees, so how far away a code
        // can be read depends on the frame's pixels: 12 MP reaches about twice as far as 3 MP.
        fourThreeTarget = CaptureFormatTarget(
            targetAspectRatio = 4.0 / 3.0,
            preferredPixels = 4032 * 3024,
            minimumPixels = 1440 * 1080,
            maximumPixels = 4032 * 3024
        ),
        wideFallbackTarget = CaptureFormatTarget(
            targetAspectRatio = 16.0 / 9.0,
            preferredPixels = 2560 * 1440,
            minimumPixels = 1920 * 1080,
            maximumPixels = 3072 * 1728
        )
    )
}

private val SCANNER_CAMERA_DEVICE_TYPES = listOf(
    AVCaptureDeviceTypeBuiltInDualWideCamera,
    AVCaptureDeviceTypeBuiltInTripleCamera,
    AVCaptureDeviceTypeBuiltInWideAngleCamera,
    AVCaptureDeviceTypeBuiltInUltraWideCamera
)
