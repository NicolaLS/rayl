package xyz.lilsus.raylsuite.core.camera

/** The shared start/stop contract implemented by CameraX on Android and AVFoundation on iOS. */
interface QrScannerController {
    /**
     * [onQrCodesScanned] gets the codes that came into view together, most prominent first and
     * never none. A code is handed on once for as long as it stays in view.
     */
    fun start(
        onQrCodesScanned: (List<String>) -> Unit,
        onCameraPermissionMissing: () -> Unit = {},
        onScannerUnavailable: () -> Unit = {}
    ): Boolean

    fun stop()
}
