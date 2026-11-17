package xyz.lilsus.raylsuite.feature.paymentui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import xyz.lilsus.raylsuite.core.camera.CameraAuthorizationState
import xyz.lilsus.raylsuite.core.camera.rememberCameraPermissionState
import xyz.lilsus.raylsuite.core.camera.rememberQrScannerController
import xyz.lilsus.raylsuite.feature.paymenthub.host.HubSavePrompt
import xyz.lilsus.raylsuite.feature.paymenthub.host.HubSavePromptBottomSheet
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubIntent
import xyz.lilsus.raylsuite.feature.paymentui.R
import xyz.lilsus.raylsuite.feature.paymentui.components.BottomLayout
import xyz.lilsus.raylsuite.feature.paymentui.components.ConfirmationBottomSheet
import xyz.lilsus.raylsuite.feature.paymentui.components.ManualAmountScreen
import xyz.lilsus.raylsuite.feature.paymentui.components.PaymentHero
import xyz.lilsus.raylsuite.feature.paymentui.components.ResultLayout

/**
 * The Scan tab: the scanner and every payment presentation. It owns the app's single camera
 * lifecycle, which runs only while this tab is selected and the screen is resumed.
 */
@Composable
fun PaymentScanScreen(
    state: PaymentFlowState,
    messageEvents: Flow<PaymentMessage>,
    appTitle: String,
    savePrompt: HubSavePrompt?,
    onIntent: (PaymentIntent) -> Unit,
    onHubIntent: (PaymentHubIntent) -> Unit,
    onOpenTransaction: (String) -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    onOpenRecent: (() -> Unit)? = null
) {
    val cameraPermission = rememberCameraPermissionState()
    val scannerController = rememberQrScannerController()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val screenResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val snackbarHostState = remember { SnackbarHostState() }
    val latestOnIntent by rememberUpdatedState(onIntent)

    var scannerStarted by remember { mutableStateOf(false) }
    var scannerRestartRequest by remember { mutableStateOf(0) }
    var scannerAutoRestartAttempts by remember { mutableStateOf(0) }
    var scannerUnavailable by remember { mutableStateOf(false) }

    val scannerShouldRun =
        isActive &&
            screenResumed &&
            state.payment == PaymentScreenState.Active &&
            savePrompt == null &&
            cameraPermission.hasPermission &&
            !scannerUnavailable

    fun requestCameraPermissionAfterScannerFailure() {
        scannerStarted = false
        scannerController.stop()
        cameraPermission.refresh()
        if (cameraPermission.authorization == CameraAuthorizationState.NOT_DETERMINED) {
            cameraPermission.request()
        }
    }

    fun handleScannerUnavailable() {
        scannerStarted = false
        if (scannerAutoRestartAttempts >= MAX_SCANNER_AUTO_RESTARTS) {
            scannerUnavailable = true
            return
        }
        scannerAutoRestartAttempts += 1
        scannerRestartRequest += 1
    }

    fun startScannerIfNeeded(): Boolean {
        if (!scannerShouldRun) return false
        if (scannerStarted) return true
        scannerStarted =
            scannerController.start(
                onQrCodesScanned = { rawValues ->
                    scannerAutoRestartAttempts = 0
                    onIntent(PaymentIntent.QrCodeScanned(rawValues))
                },
                onCameraPermissionMissing = ::requestCameraPermissionAfterScannerFailure,
                onScannerUnavailable = ::handleScannerUnavailable
            )
        return scannerStarted
    }

    DisposableEffect(scannerController) {
        onDispose {
            scannerController.stop()
            scannerStarted = false
        }
    }

    var inputRejections by remember { mutableIntStateOf(0) }
    LaunchedEffect(messageEvents) {
        messageEvents.collectLatest { message ->
            if (message.rejectsInput) inputRejections++
            snackbarHostState.showSnackbar(message.text)
        }
    }

    LaunchedEffect(state.transactionDetailNavigationTarget) {
        state.transactionDetailNavigationTarget?.let { id ->
            onOpenTransaction(id)
            onIntent(PaymentIntent.TransactionDetailNavigationHandled(id))
        }
    }

    LaunchedEffect(lifecycleState) {
        if (screenResumed) cameraPermission.refresh()
    }

    LaunchedEffect(isActive, screenResumed, cameraPermission.authorization) {
        if (!isActive || !screenResumed) return@LaunchedEffect
        when (cameraPermission.authorization) {
            CameraAuthorizationState.AUTHORIZED -> {
                scannerAutoRestartAttempts = 0
                scannerUnavailable = false
            }

            CameraAuthorizationState.NOT_DETERMINED -> cameraPermission.request()

            CameraAuthorizationState.DENIED,
            CameraAuthorizationState.RESTRICTED,
            CameraAuthorizationState.UNAVAILABLE -> {
                if (scannerStarted) {
                    scannerController.stop()
                    scannerStarted = false
                }
            }
        }
    }

    LaunchedEffect(state.payment) {
        if (state.payment == PaymentScreenState.Active) {
            scannerAutoRestartAttempts = 0
            scannerUnavailable = false
        }
    }

    LaunchedEffect(scannerShouldRun) {
        if (!scannerShouldRun) {
            if (scannerStarted) {
                scannerController.stop()
                scannerStarted = false
            }
            scannerAutoRestartAttempts = 0
            return@LaunchedEffect
        }
        scannerAutoRestartAttempts = 0
        startScannerIfNeeded()
    }

    LaunchedEffect(scannerRestartRequest, scannerShouldRun) {
        if (scannerRestartRequest == 0) return@LaunchedEffect
        delay(SCANNER_AUTO_RESTART_DELAY_MS)
        if (scannerShouldRun && !scannerStarted) startScannerIfNeeded()
    }

    var showResolvingContent by remember { mutableStateOf(false) }
    val isResolvingLoading = state.payment is PaymentScreenState.Loading &&
        state.payment.kind == PaymentLoadingKind.Resolving
    LaunchedEffect(state.payment) {
        showResolvingContent = false
        if (isResolvingLoading) {
            delay(RESOLVING_PAYMENT_INDICATOR_DELAY_MS)
            showResolvingContent = true
        }
    }

    val contentState = if (isResolvingLoading && !showResolvingContent) {
        PaymentScreenState.Active
    } else {
        state.payment
    }
    val effectiveCameraAuthorization =
        if (scannerUnavailable) {
            CameraAuthorizationState.UNAVAILABLE
        } else {
            cameraPermission.authorization
        }
    val canDismissResult = savePrompt == null &&
        (state.payment is PaymentScreenState.Success || state.payment is PaymentScreenState.Error)

    Scaffold(
        modifier = modifier.testTag(PaymentTestTags.SCREEN),
        floatingActionButton = {
            // Session history becomes available after the first payment.
            if (onOpenRecent != null && state.sessionItems.isNotEmpty() &&
                state.payment !is PaymentScreenState.Loading
            ) {
                RecentPaymentsFab(
                    newTransactionCount = state.newSessionTransactionCount,
                    onClick = onOpenRecent
                )
            }
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .pointerInput(canDismissResult) {
                    if (canDismissResult) {
                        detectTapGestures { latestOnIntent(PaymentIntent.DismissResult) }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PaymentHero(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.5f),
                phase = state.payment.toHeroPhase(),
                rejections = inputRejections
            )
            Crossfade(targetState = contentState) { current ->
                when {
                    current is PaymentScreenState.Success ||
                        current is PaymentScreenState.Error -> ResultLayout(
                        modifier = Modifier.fillMaxSize(),
                        result = current.toResultPresentation(),
                        onContinue = { onIntent(PaymentIntent.DismissResult) }
                    )

                    current is PaymentScreenState.Loading &&
                        current.kind == PaymentLoadingKind.Resolving -> {
                        ResolvingPaymentLayout(modifier = Modifier.fillMaxSize())
                    }

                    current == PaymentScreenState.Active &&
                        effectiveCameraAuthorization.requiresRecovery ->
                        CameraPermissionLayout(
                            authorization = effectiveCameraAuthorization,
                            canRequestPermission = cameraPermission.canRequestPermission,
                            onRequestPermission = cameraPermission::request,
                            onOpenSettings = cameraPermission::openSettings,
                            modifier = Modifier.fillMaxSize()
                        )

                    else ->
                        BottomLayout(
                            title = appTitle,
                            subtitle = stringResource(R.string.point_camera_message_subtitle)
                        )
                }
            }
        }
    }

    if (state.payment is PaymentScreenState.EnterAmount) {
        val enterAmount = state.payment
        ManualAmountScreen(
            state = enterAmount.entry,
            lnurlPayDisplay = enterAmount.lnurlPayDisplay,
            onKeyPress = { onIntent(PaymentIntent.ManualAmountKeyPress(it)) },
            onSubmit = { onIntent(PaymentIntent.ManualAmountSubmit) },
            onDismiss = { onIntent(PaymentIntent.ManualAmountDismiss) }
        )
    }

    if (state.payment is PaymentScreenState.Confirm) {
        val confirm = state.payment
        ConfirmationBottomSheet(
            amount = confirm.amount,
            lnurlPayDisplay = confirm.lnurlPayDisplay,
            fundingSource = confirm.fundingSource,
            onPay = { onIntent(PaymentIntent.ConfirmPaymentSubmit) },
            onDismiss = { onIntent(PaymentIntent.ConfirmPaymentDismiss) }
        )
    }

    savePrompt?.let { prompt ->
        HubSavePromptBottomSheet(
            prompt = prompt,
            onTitleChange = { onHubIntent(PaymentHubIntent.SavePromptTitleChanged(it)) },
            onSave = { onHubIntent(PaymentHubIntent.SavePromptSave) },
            onDismiss = { onHubIntent(PaymentHubIntent.SavePromptDismiss) }
        )
    }
}

@Composable
private fun CameraPermissionLayout(
    authorization: CameraAuthorizationState,
    canRequestPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val restricted = authorization != CameraAuthorizationState.DENIED
    Column(
        modifier = modifier.padding(top = 16.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text =
                stringResource(
                    if (restricted) {
                        R.string.camera_permission_restricted_title
                    } else {
                        R.string.camera_permission_denied_title
                    }
                ),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            modifier = Modifier.padding(top = 8.dp),
            text =
                stringResource(
                    if (restricted) {
                        R.string.camera_permission_restricted_body
                    } else {
                        R.string.camera_permission_denied_body
                    }
                ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        if (!restricted) {
            Button(
                modifier = Modifier.padding(top = 16.dp),
                onClick = if (canRequestPermission) onRequestPermission else onOpenSettings
            ) {
                Text(
                    stringResource(
                        if (canRequestPermission) {
                            R.string.camera_permission_retry
                        } else {
                            R.string.camera_permission_open_settings
                        }
                    )
                )
            }
        }
    }
}

private val CameraAuthorizationState.requiresRecovery: Boolean
    get() =
        this == CameraAuthorizationState.DENIED ||
            this == CameraAuthorizationState.RESTRICTED ||
            this == CameraAuthorizationState.UNAVAILABLE

@Composable
private fun ResolvingPaymentLayout(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(top = 16.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.resolving_payment_title),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            modifier = Modifier.padding(top = 8.dp),
            text = stringResource(R.string.resolving_payment_subtitle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center
        )
    }
}

private const val SCANNER_AUTO_RESTART_DELAY_MS = 350L
private const val MAX_SCANNER_AUTO_RESTARTS = 5
private const val RESOLVING_PAYMENT_INDICATOR_DELAY_MS = 1_000L

@Composable
private fun RecentPaymentsFab(newTransactionCount: Int, onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier.testTag(PaymentTestTags.SESSION_TRANSACTIONS_BUTTON)
    ) {
        BadgedBox(
            badge = {
                if (newTransactionCount > 0) {
                    Badge { Text(text = newTransactionCount.badgeLabel()) }
                }
            }
        ) {
            Icon(
                imageVector = Icons.Filled.History,
                contentDescription = stringResource(R.string.view_session_transactions)
            )
        }
    }
}

private fun Int.badgeLabel(): String = if (this > MAX_RECENT_BADGE_COUNT) {
    "$MAX_RECENT_BADGE_COUNT+"
} else {
    toString()
}

private const val MAX_RECENT_BADGE_COUNT = 99
