package xyz.lilsus.raylsuite.core.camera

/**
 * Replaces the camera in the E2E app builds, so UI flows (Maestro) can "scan" a payment request
 * on emulators and simulators. Only the E2E entry points call [install]; in every other build
 * the scanner factories return the real camera.
 */
object TestQrInput {
    private var installed = false
    private var pending: String? = null

    /**
     * From now on, scanners started in this process are fakes. The first one started "reads"
     * [value] after a moment, once; after that they see nothing, like a camera pointed at a code
     * once. `null` installs a camera that never reads anything.
     */
    fun install(value: String?) {
        installed = true
        pending = value?.takeIf(String::isNotBlank)
    }

    internal fun scannerOrNull(
        schedule: (delayMillis: Long, block: () -> Unit) -> Cancel
    ): QrScannerController? = if (installed) FakeQrScanner(schedule) else null

    /** Cancels a scheduled block. */
    fun interface Cancel {
        fun cancel()
    }

    private class FakeQrScanner(
        private val schedule: (delayMillis: Long, block: () -> Unit) -> Cancel
    ) : QrScannerController {
        private var delivery: Cancel? = null

        override fun start(
            onQrCodeScanned: (String) -> Unit,
            onCameraPermissionMissing: () -> Unit,
            onScannerUnavailable: () -> Unit
        ): Boolean {
            if (pending != null) {
                // A real camera reports a code a moment after it starts, never during start().
                delivery = schedule(READ_DELAY_MILLIS) {
                    delivery = null
                    pending?.let { value ->
                        pending = null
                        onQrCodeScanned(value)
                    }
                }
            }
            return true
        }

        override fun stop() {
            delivery?.cancel()
            delivery = null
        }
    }

    private const val READ_DELAY_MILLIS = 750L
}
