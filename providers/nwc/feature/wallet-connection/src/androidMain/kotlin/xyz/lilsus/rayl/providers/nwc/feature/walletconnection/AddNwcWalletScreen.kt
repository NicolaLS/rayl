package xyz.lilsus.rayl.providers.nwc.feature.walletconnection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import xyz.lilsus.raylsuite.core.camera.CameraAuthorizationState
import xyz.lilsus.raylsuite.core.camera.rememberQrScannerController
import xyz.lilsus.raylsuite.core.ui.components.CredentialConnectionScreen
import xyz.lilsus.raylsuite.core.ui.components.CredentialInput

@Composable
fun AddNwcWalletScreen(
    state: AddNwcWalletUiState,
    onBack: (() -> Unit)?,
    onUriChange: (String) -> Unit,
    onPaste: () -> Unit,
    onSubmit: () -> Unit,
    onQrCodeScanned: (String) -> Unit,
    onCameraPermissionAction: () -> Unit,
    cameraAuthorization: CameraAuthorizationState,
    canRequestCameraPermission: Boolean,
    modifier: Modifier = Modifier,
    privacyPolicyUrl: String? = null,
    termsUrl: String? = null
) {
    val focusManager = LocalFocusManager.current
    val scanner = rememberQrScannerController()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val screenResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val currentOnQrCodeScanned by rememberUpdatedState(onQrCodeScanned)
    val currentOnCameraPermissionAction by rememberUpdatedState(onCameraPermissionAction)
    var scannerUnavailable by remember { mutableStateOf(false) }
    val submitOrClearFocus = {
        focusManager.clearFocus(force = true)
        onSubmit()
    }

    DisposableEffect(scanner, cameraAuthorization, screenResumed) {
        scannerUnavailable = false
        if (screenResumed && cameraAuthorization == CameraAuthorizationState.AUTHORIZED) {
            scanner.start(
                onQrCodeScanned = { currentOnQrCodeScanned(it) },
                onCameraPermissionMissing = { currentOnCameraPermissionAction() },
                onScannerUnavailable = { scannerUnavailable = true }
            )
        }
        onDispose(scanner::stop)
    }

    CredentialConnectionScreen(
        title = stringResource(R.string.add_wallet_title),
        description = stringResource(R.string.add_wallet_description),
        actionTitle = stringResource(xyz.lilsus.raylsuite.core.ui.R.string.credential_connect),
        canSubmit = state.isUriValid,
        isSubmitting = false,
        onSubmit = submitOrClearFocus,
        onBack = onBack,
        privacyPolicyUrl = privacyPolicyUrl,
        termsUrl = termsUrl,
        modifier = modifier.testTag(NwcWalletConnectionTestTags.SCREEN),
        actionModifier = Modifier.testTag(NwcWalletConnectionTestTags.CONNECT_BUTTON)
    ) {
        // Scanning is the quickest way in, and it needs no tap: it comes first.
        CameraCard(
            authorization = cameraAuthorization,
            scannerUnavailable = scannerUnavailable,
            canRequestPermission = canRequestCameraPermission,
            onCameraPermissionAction = onCameraPermissionAction
        )
        CredentialInput(
            value = state.uri,
            onValueChange = onUriChange,
            label = stringResource(R.string.add_wallet_uri_label),
            placeholder = stringResource(R.string.add_wallet_uri_placeholder),
            onPaste = onPaste,
            onSubmit = { if (state.isUriValid) submitOrClearFocus() },
            fieldModifier = Modifier.testTag(NwcWalletConnectionTestTags.URI_FIELD),
            pasteModifier = Modifier.testTag(NwcWalletConnectionTestTags.PASTE_BUTTON)
        )
        state.error?.let { error ->
            Text(
                text = nwcConnectionErrorMessageFor(error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun CameraCard(
    authorization: CameraAuthorizationState,
    scannerUnavailable: Boolean,
    canRequestPermission: Boolean,
    onCameraPermissionAction: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(NwcWalletConnectionTestTags.CAMERA_CARD)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (authorization == CameraAuthorizationState.AUTHORIZED) {
                    Icons.Default.QrCodeScanner
                } else {
                    Icons.Default.PhotoCamera
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)
            )
            Text(
                text = stringResource(
                    when {
                        scannerUnavailable -> R.string.add_wallet_scan_unavailable

                        authorization == CameraAuthorizationState.AUTHORIZED ->
                            R.string.add_wallet_scan_instruction

                        authorization == CameraAuthorizationState.RESTRICTED ||
                            authorization == CameraAuthorizationState.UNAVAILABLE ->
                            R.string.add_wallet_scan_restricted

                        else -> R.string.add_wallet_scan_permission
                    }
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (authorization != CameraAuthorizationState.AUTHORIZED) {
                if (
                    authorization == CameraAuthorizationState.NOT_DETERMINED ||
                    authorization == CameraAuthorizationState.DENIED
                ) {
                    Button(onClick = onCameraPermissionAction) {
                        Text(
                            stringResource(
                                if (
                                    authorization == CameraAuthorizationState.DENIED &&
                                    !canRequestPermission
                                ) {
                                    R.string.add_wallet_scan_open_settings
                                } else if (authorization == CameraAuthorizationState.DENIED) {
                                    R.string.add_wallet_scan_retry
                                } else {
                                    R.string.add_wallet_scan_allow_camera
                                }
                            )
                        )
                    }
                }
            }
        }
    }
}

object NwcWalletConnectionTestTags {
    const val SCREEN = "nwc_wallet_screen"
    const val URI_FIELD = "nwc_wallet_uri_field"
    const val PASTE_BUTTON = "nwc_wallet_paste_button"
    const val CONNECT_BUTTON = "nwc_wallet_connect_button"
    const val CAMERA_CARD = "nwc_wallet_camera_card"
    const val CONFIRM_DIALOG = "nwc_wallet_confirm_dialog"
    const val DIALOG_LOADING = "nwc_wallet_dialog_loading"
    const val DIALOG_DETAILS = "nwc_wallet_dialog_details"
    const val DIALOG_ALIAS_FIELD = "nwc_wallet_dialog_alias_field"
    const val DIALOG_WARNING = "nwc_wallet_dialog_warning"
    const val DIALOG_CONFIRM_BUTTON = "nwc_wallet_dialog_confirm_button"
    const val DIALOG_CANCEL_BUTTON = "nwc_wallet_dialog_cancel_button"
    const val DIALOG_RETRY_BUTTON = "nwc_wallet_dialog_retry_button"
}
