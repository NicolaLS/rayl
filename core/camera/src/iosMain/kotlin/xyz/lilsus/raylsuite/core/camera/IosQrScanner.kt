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
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMVideoFormatDescriptionGetDimensions
import platform.Foundation.NSError
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
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
            observeQrCode(selectPreferredMetadataValue(metadataObjects))
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
        if (hasStartedOnce) {
            IosCameraTrace.event(CameraTraceEvent.RESTART)
        }
        hasStartedOnce = true
        generation += 1
        val currentGeneration = generation
        val startTrace = IosCameraTrace.beginInterval(CameraTraceEvent.START_TO_READY)
        dispatch_async(sessionQueue) {
            try {
                if (generation != currentGeneration) return@dispatch_async
                qrPresenceGate.reset()
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
        val stopTrace = IosCameraTrace.beginInterval(CameraTraceEvent.STOP)
        dispatch_async(sessionQueue) {
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
        if (!session.running && started) session.startRunning()
    }

    private fun configureSession(): Boolean {
        val configurationTrace = IosCameraTrace.beginInterval(CameraTraceEvent.CONFIGURE_SESSION)
        try {
            val selection = selectScannerCamera() ?: return false
            val device = selection.device
            val input = createDeviceInput(device) ?: return false
            val metadataOutput = AVCaptureMetadataOutput()

            session.beginConfiguration()
            return try {
                removeAllInputsAndOutputs()
                val preferredFormatApplied = applyPreferredCaptureFormat(
                    device = device,
                    target = selection.formatTarget
                )
                val sessionPreset = if (preferredFormatApplied) {
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

    private fun observeQrCode(value: String?) {
        val emittedValue = qrPresenceGate.observe(value) ?: return
        IosCameraTrace.event(CameraTraceEvent.QR_DETECTED)
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

private data class CaptureFormatTarget(
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

private fun selectScannerCamera(): ScannerCameraSelection? {
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
        virtualWide != null -> ScannerCameraSelection(virtualWide, FOUR_THREE_CAPTURE_TARGET)

        ultraWide != null -> ScannerCameraSelection(ultraWide, FOUR_THREE_CAPTURE_TARGET)

        wide != null -> ScannerCameraSelection(wide, WIDE_FALLBACK_CAPTURE_TARGET)

        else -> AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)?.let {
            ScannerCameraSelection(it, WIDE_FALLBACK_CAPTURE_TARGET)
        }
    }
}

private fun applyPreferredCaptureFormat(
    device: AVCaptureDevice,
    target: CaptureFormatTarget
): Boolean {
    @Suppress("UNCHECKED_CAST")
    val formats = device.formats as? List<AVCaptureDeviceFormat> ?: return false
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
        if (maximumFrameRate < TARGET_CAPTURE_FPS) return@mapNotNull null
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

    return ranked.any { candidate -> applyCaptureFormat(device, candidate) }
}

private fun applyCaptureFormat(
    device: AVCaptureDevice,
    candidate: CaptureFormatCandidate
): Boolean = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    if (!device.lockForConfiguration(error.ptr)) return@memScoped false
    try {
        val frameDuration = CMTimeMake(
            value = 1,
            timescale = TARGET_CAPTURE_FPS.toInt()
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

private const val TARGET_CAPTURE_FPS = 30.0
private const val MAXIMUM_ASPECT_DELTA = 0.08

private val FOUR_THREE_CAPTURE_TARGET = CaptureFormatTarget(
    targetAspectRatio = 4.0 / 3.0,
    preferredPixels = 1920 * 1440,
    minimumPixels = 1440 * 1080,
    maximumPixels = 2304 * 1728
)

private val WIDE_FALLBACK_CAPTURE_TARGET = CaptureFormatTarget(
    targetAspectRatio = 16.0 / 9.0,
    preferredPixels = 2560 * 1440,
    minimumPixels = 1920 * 1080,
    maximumPixels = 3072 * 1728
)

private val SCANNER_CAMERA_DEVICE_TYPES = listOf(
    AVCaptureDeviceTypeBuiltInDualWideCamera,
    AVCaptureDeviceTypeBuiltInTripleCamera,
    AVCaptureDeviceTypeBuiltInWideAngleCamera,
    AVCaptureDeviceTypeBuiltInUltraWideCamera
)
