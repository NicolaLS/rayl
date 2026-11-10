@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class
)

package xyz.lilsus.raylsuite.core.camera

import kotlin.math.abs
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
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
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetHigh
import platform.AVFoundation.AVCaptureSessionPresetInputPriority
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.AVFoundation.AVFrameRateRange
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.deviceType
import platform.AVFoundation.exposureMode
import platform.AVFoundation.focusMode
import platform.AVFoundation.isExposureModeSupported
import platform.AVFoundation.isFocusModeSupported
import platform.AVFoundation.maxAvailableVideoZoomFactor
import platform.AVFoundation.minAvailableVideoZoomFactor
import platform.AVFoundation.videoZoomFactor
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
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
import platform.Vision.VNImageRequestHandler
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
import xyz.lilsus.raylsuite.core.raylapi.TelemetryScanDecoder
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
    private val metadataDelegate = MetadataDelegate(
        isActive = { started },
        onMetadataObjects = { metadataObjects ->
            observeQrCode(
                TelemetryScanDecoder.AvFoundation,
                selectPreferredMetadataValue(metadataObjects),
                atMs = RaylTrace.nowMs()
            )
        }
    )

    // Vision decodes on its own queue so its work never delays the metadata reader's callbacks;
    // both report to the session queue with the time they decoded at.
    private val visionQueue: dispatch_queue_t = dispatch_queue_create(
        "xyz.lilsus.raylsuite.qr.vision",
        null
    )
    private val visionDelegate = VisionDelegate(
        isActive = { started },
        onFrameAnalyzed = { durationMs, width, height ->
            scanTelemetry.frameAnalyzed(durationMs, width, height)
        },
        onValue = { value, atMs ->
            dispatch_async(sessionQueue) {
                observeQrCode(TelemetryScanDecoder.Vision, value, atMs)
            }
        }
    )

    private var onQrCodeScanned: ((String) -> Unit)? = null
    private var onScannerUnavailable: (() -> Unit)? = null
    private var lifecycleObserver: NSObjectProtocol? = null
    private var started = false
    private var configured = false
    private val qrPresenceGate = QrPresenceGate()
    private var hasStartedOnce = false
    private var generation = 0L
    private val config = CameraVariant.current.iosCameraConfig()
    private val scanTelemetry = ScanTelemetry()
    private val race = DecoderRace(scanTelemetry)
    private var racing = false

    override fun start(
        onQrCodeScanned: (String) -> Unit,
        onCameraPermissionMissing: () -> Unit,
        onScannerUnavailable: () -> Unit
    ): Boolean {
        if (!isNativeCameraAuthorized()) {
            onCameraPermissionMissing()
            return false
        }
        this.onQrCodeScanned = onQrCodeScanned
        this.onScannerUnavailable = onScannerUnavailable
        ensureLifecycleObserver()
        if (hasStartedOnce) RaylTrace.mark(TracePoint.CameraRestart)
        hasStartedOnce = true
        generation += 1
        val currentGeneration = generation
        scanTelemetry.sessionStarted()
        val startTrace = RaylTrace.begin(TraceSpan.CameraStartToReady)
        dispatch_async(sessionQueue) {
            try {
                if (generation != currentGeneration) return@dispatch_async
                qrPresenceGate.reset()
                race.reset()
                started = true
                ensureSessionRunning()
            } finally {
                startTrace?.end()
            }
        }
        return true
    }

    override fun stop() {
        generation += 1
        removeLifecycleObserver()
        onQrCodeScanned = null
        onScannerUnavailable = null
        val stopTrace = RaylTrace.begin(TraceSpan.CameraStop)
        dispatch_async(sessionQueue) {
            try {
                started = false
                scanTelemetry.sessionEnded()
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
            // Blocks until the session delivers frames; AVFoundation decodes them itself, so this
            // is the closest there is to a first frame.
            session.startRunning()
            scanTelemetry.ready()
            scanTelemetry.firstFrame()
        }
    }

    private fun configureSession(): Boolean {
        val configurationTrace = RaylTrace.begin(TraceSpan.CameraConfigureSession)
        try {
            val selection = selectScannerCamera(config) ?: return false
            val device = selection.device
            val input = createDeviceInput(device) ?: return false
            val metadataOutput = AVCaptureMetadataOutput()

            session.beginConfiguration()
            return try {
                removeAllInputsAndOutputs()
                val appliedFormat = applyPreferredCaptureFormat(
                    device = device,
                    target = selection.formatTarget,
                    captureFps = config.captureFps
                )
                appliedFormat?.let { scanTelemetry.frameSize(it.width, it.height) }
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
                if (!session.canAddOutput(metadataOutput)) return false
                session.addOutput(metadataOutput)
                metadataOutput.setMetadataObjectsDelegate(metadataDelegate, sessionQueue)
                @Suppress("UNCHECKED_CAST")
                val availableTypes =
                    metadataOutput.availableMetadataObjectTypes as? List<Any> ?: emptyList()
                if (availableTypes.none { it == AVMetadataObjectTypeQRCode }) return false
                metadataOutput.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
                racing = config.raceVision && addVisionOutput()
                applyFixedCameraControls(device)
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

    /** A second output on the same session, feeding the camera's frames to Vision. */
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
    private fun observeQrCode(decoder: TelemetryScanDecoder, value: String?, atMs: Long) {
        val emittedValue = if (racing) {
            race.observe(decoder, value, atMs)
        } else {
            qrPresenceGate.observe(value)?.also { scanTelemetry.qrDetected(decoder) }
        } ?: return
        val callback = onQrCodeScanned ?: return
        val currentGeneration = generation
        dispatch_async(dispatch_get_main_queue()) {
            if (started && generation == currentGeneration) callback(emittedValue)
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
 * Reads QR codes from the camera's frames with Apple's Vision framework, one frame at a time.
 * It reports every frame's result, also "no code", like the metadata reader does.
 */
private class VisionDelegate(
    private val isActive: () -> Boolean,
    private val onFrameAnalyzed: (durationMs: Long, width: Int, height: Int) -> Unit,
    private val onValue: (value: String?, atMs: Long) -> Unit
) : NSObject(),
    AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
    private val request = VNDetectBarcodesRequest().apply {
        symbologies = listOf(VNBarcodeSymbologyQR)
    }

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputSampleBuffer: CMSampleBufferRef?,
        fromConnection: AVCaptureConnection
    ) {
        if (!isActive()) return
        val pixelBuffer = CMSampleBufferGetImageBuffer(didOutputSampleBuffer) ?: return
        val startedMs = RaylTrace.nowMs()
        val handler = VNImageRequestHandler(pixelBuffer, options = emptyMap<Any?, Any>())
        val performed = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            handler.performRequests(listOf(request), error.ptr)
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
        onValue(pickPreferredQrValue(candidates, frameWidth = 1f, frameHeight = 1f), now)
    }
}

private class MetadataDelegate(
    private val isActive: () -> Boolean,
    private val onMetadataObjects: (List<*>) -> Unit
) : NSObject(),
    AVCaptureMetadataOutputObjectsDelegateProtocol {
    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection
    ) {
        if (isActive()) onMetadataObjects(didOutputMetadataObjects)
    }
}

private fun selectPreferredMetadataValue(metadataObjects: List<*>): String? {
    val candidates = metadataObjects.mapNotNull { objectCandidate ->
        val code = objectCandidate as? AVMetadataMachineReadableCodeObject ?: return@mapNotNull null
        val value = code.stringValue?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
        code.bounds.useContents {
            val left = origin.x.toFloat()
            val top = origin.y.toFloat()
            val width = size.width.toFloat()
            val height = size.height.toFloat()
            QrDetectionCandidate(
                value = value,
                left = left,
                top = top,
                right = left + width,
                bottom = top + height
            )
        }
    }
    return pickPreferredQrValue(candidates, frameWidth = 1f, frameHeight = 1f)
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

private fun applyFixedCameraControls(device: AVCaptureDevice) = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    if (!device.lockForConfiguration(error.ptr)) return@memScoped
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
    } finally {
        device.unlockForConfiguration()
    }
}

private const val MAXIMUM_ASPECT_DELTA = 0.08

/**
 * The scanner settings a [CameraVariant] stands for on iOS: the capture rate, the format wanted
 * from a camera with an ultra-wide lens, the one wanted from a wide-angle-only camera, and
 * whether Vision reads the frames next to AVFoundation's metadata reader. With [raceVision] the
 * first of the two to decode a code hands it on, and usage data learns which one it was.
 */
internal class IosCameraConfig(
    val raceVision: Boolean,
    val captureFps: Double,
    val fourThreeTarget: CaptureFormatTarget,
    val wideFallbackTarget: CaptureFormatTarget
)

internal fun CameraVariant.iosCameraConfig(): IosCameraConfig = when (this) {
    CameraVariant.Control -> IosCameraConfig(
        raceVision = true,
        captureFps = 30.0,
        fourThreeTarget = CaptureFormatTarget(
            targetAspectRatio = 4.0 / 3.0,
            preferredPixels = 1920 * 1440,
            minimumPixels = 1440 * 1080,
            maximumPixels = 2304 * 1728
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
