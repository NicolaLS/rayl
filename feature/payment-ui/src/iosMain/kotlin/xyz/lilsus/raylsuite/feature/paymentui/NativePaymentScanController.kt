package xyz.lilsus.raylsuite.feature.paymentui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.lilsus.raylsuite.core.camera.CameraAuthorizationState
import xyz.lilsus.raylsuite.core.camera.createNativeQrScannerController
import xyz.lilsus.raylsuite.core.camera.nativeCameraAuthorizationState
import xyz.lilsus.raylsuite.core.camera.requestNativeCameraPermission
import xyz.lilsus.raylsuite.feature.paymenthub.host.HubSavePrompt
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubIntent
import xyz.lilsus.raylsuite.feature.paymentui.amount.ManualAmountKey

/**
 * Native Scan host boundary. SwiftUI owns layout and presentation; this object keeps shared
 * payment intents, QR scanning, and localized snapshots.
 */
class NativePaymentScanController(
    private val onPaymentIntent: (PaymentIntent) -> Unit,
    private val onHubIntent: (PaymentHubIntent) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val scanner = createNativeQrScannerController()
    private val snapshot = MutableStateFlow<NativePaymentScanSnapshot?>(null)
    private val messages = MutableSharedFlow<PaymentMessage>(extraBufferCapacity = 8)

    private var input: NativePaymentScanInput? = null
    private var screenActive = false
    private var scannerStarted = false
    private var scannerRestartAttempts = 0
    private var scannerRestartJob: Job? = null
    private var resolvingContentJob: Job? = null
    private var permissionRequested = false
    private var cameraAuthorization = nativeCameraAuthorizationState()
    private var scannerUnavailable = false
    private var showResolvingContent = false

    fun observe(onChange: (NativePaymentScanSnapshot) -> Unit): () -> Unit {
        val job = scope.launch { snapshot.filterNotNull().collect(onChange) }
        return { job.cancel() }
    }

    fun observeMessages(onMessage: (PaymentMessage) -> Unit): () -> Unit {
        val job = scope.launch { messages.collect(onMessage) }
        return { job.cancel() }
    }

    suspend fun update(
        payment: PaymentScreenState,
        appTitle: String,
        savePrompt: HubSavePrompt?,
        recentCount: Int = 0,
        newRecentCount: Int = 0
    ) {
        val previousPayment = input?.payment
        val wasResolving = previousPayment.isResolving()
        val isResolving = payment.isResolving()
        if (payment == PaymentScreenState.Active && previousPayment != PaymentScreenState.Active) {
            scannerUnavailable = false
        }
        if (!isResolving) {
            resolvingContentJob?.cancel()
            showResolvingContent = false
        } else if (!wasResolving) {
            showResolvingContent = false
            resolvingContentJob?.cancel()
            resolvingContentJob =
                scope.launch {
                    delay(RESOLVING_PAYMENT_INDICATOR_DELAY_MS)
                    showResolvingContent = true
                    publishSnapshot()
                }
        }
        input =
            NativePaymentScanInput(
                payment = payment,
                appTitle = appTitle,
                savePrompt = savePrompt,
                recentCount = recentCount,
                newRecentCount = newRecentCount
            )
        publishSnapshot()
        reconcileScanner()
    }

    fun emitMessage(message: PaymentMessage) {
        messages.tryEmit(message)
    }

    fun setActive(active: Boolean) {
        if (active && !screenActive && scannerUnavailable) {
            scannerUnavailable = false
            scope.launch { publishSnapshot() }
        }
        screenActive = active
        refreshCameraAuthorization()
        reconcileScanner()
    }

    fun refreshCameraAuthorization() {
        val next = nativeCameraAuthorizationState()
        if (next == cameraAuthorization) return
        cameraAuthorization = next
        if (next == CameraAuthorizationState.AUTHORIZED) scannerUnavailable = false
        scope.launch { publishSnapshot() }
    }

    fun dismissResult() {
        onPaymentIntent(PaymentIntent.DismissResult)
    }

    fun manualAmountKey(key: String) {
        val intent =
            when (key) {
                "switchCurrency" -> PaymentIntent.ManualAmountKeyPress(
                    ManualAmountKey.SwitchCurrency
                )

                "decimal" -> PaymentIntent.ManualAmountKeyPress(ManualAmountKey.Decimal)

                "backspace" -> PaymentIntent.ManualAmountKeyPress(ManualAmountKey.Backspace)

                else ->
                    key.toIntOrNull()
                        ?.takeIf { it in 0..9 }
                        ?.let { PaymentIntent.ManualAmountKeyPress(ManualAmountKey.Digit(it)) }
            } ?: return
        onPaymentIntent(intent)
    }

    fun submitManualAmount() {
        onPaymentIntent(PaymentIntent.ManualAmountSubmit)
    }

    fun dismissManualAmount() {
        onPaymentIntent(PaymentIntent.ManualAmountDismiss)
    }

    fun submitConfirmation() {
        onPaymentIntent(PaymentIntent.ConfirmPaymentSubmit)
    }

    fun dismissConfirmation() {
        onPaymentIntent(PaymentIntent.ConfirmPaymentDismiss)
    }

    fun updateSaveTargetTitle(title: String) {
        onHubIntent(PaymentHubIntent.SavePromptTitleChanged(title))
    }

    fun saveTarget() {
        onHubIntent(PaymentHubIntent.SavePromptSave)
    }

    fun dismissSaveTarget() {
        onHubIntent(PaymentHubIntent.SavePromptDismiss)
    }

    fun clear() {
        screenActive = false
        scannerRestartJob?.cancel()
        resolvingContentJob?.cancel()
        scanner.stop()
        scannerStarted = false
        input = null
        snapshot.value = null
        scope.cancel()
    }

    private suspend fun publishSnapshot() {
        val current = input ?: return
        val next =
            nativePaymentScanSnapshot(
                payment = current.payment,
                appTitle = current.appTitle,
                savePrompt = current.savePrompt,
                showResolvingContent = showResolvingContent,
                recentCount = current.recentCount,
                newRecentCount = current.newRecentCount,
                cameraAuthorization =
                    if (scannerUnavailable) {
                        CameraAuthorizationState.UNAVAILABLE
                    } else {
                        cameraAuthorization
                    }
            )
        snapshot.value = next
    }

    private fun reconcileScanner() {
        // A pending permission callback can arrive after the experience has been disposed.
        if (!scope.isActive) return
        val current = input
        val shouldRun =
            screenActive &&
                current?.payment == PaymentScreenState.Active &&
                current.savePrompt == null &&
                !scannerUnavailable
        if (!shouldRun) {
            scannerRestartJob?.cancel()
            if (scannerStarted) scanner.stop()
            scannerStarted = false
            scannerRestartAttempts = 0
            return
        }
        when (cameraAuthorization) {
            CameraAuthorizationState.NOT_DETERMINED -> {
                if (!permissionRequested) {
                    permissionRequested = true
                    requestNativeCameraPermission { authorization ->
                        permissionRequested = false
                        cameraAuthorization = authorization
                        scope.launch { publishSnapshot() }
                        reconcileScanner()
                    }
                }
                return
            }

            CameraAuthorizationState.DENIED,
            CameraAuthorizationState.RESTRICTED,
            CameraAuthorizationState.UNAVAILABLE -> {
                if (scannerStarted) scanner.stop()
                scannerStarted = false
                return
            }

            CameraAuthorizationState.AUTHORIZED -> Unit
        }
        permissionRequested = false
        if (scannerStarted) return
        scannerStarted =
            scanner.start(
                onQrCodesScanned = {
                    scannerRestartAttempts = 0
                    onPaymentIntent(PaymentIntent.QrCodeScanned(it))
                },
                onCameraPermissionMissing = {
                    scannerStarted = false
                    refreshCameraAuthorization()
                    reconcileScanner()
                },
                onScannerUnavailable = {
                    scannerStarted = false
                    scheduleScannerRestart()
                }
            )
    }

    private fun scheduleScannerRestart() {
        if (scannerRestartAttempts >= MAX_SCANNER_AUTO_RESTARTS) {
            scannerUnavailable = true
            scope.launch { publishSnapshot() }
            return
        }
        scannerRestartAttempts += 1
        scannerRestartJob?.cancel()
        scannerRestartJob =
            scope.launch {
                delay(SCANNER_AUTO_RESTART_DELAY_MS)
                reconcileScanner()
            }
    }
}

private data class NativePaymentScanInput(
    val payment: PaymentScreenState,
    val appTitle: String,
    val savePrompt: HubSavePrompt?,
    val recentCount: Int,
    val newRecentCount: Int
)

private fun PaymentScreenState?.isResolving(): Boolean =
    this is PaymentScreenState.Loading && kind == PaymentLoadingKind.Resolving

private const val SCANNER_AUTO_RESTART_DELAY_MS = 350L
private const val MAX_SCANNER_AUTO_RESTARTS = 5
private const val RESOLVING_PAYMENT_INDICATOR_DELAY_MS = 1_000L
