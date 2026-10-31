package xyz.lilsus.rayl.providers.nwc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.lilsus.rayl.providers.nwc.feature.onboarding.NwcWalletConnectionOutcome
import xyz.lilsus.rayl.providers.nwc.feature.onboarding.nativeNwcOnboardingText
import xyz.lilsus.rayl.providers.nwc.feature.onboarding.nwcWalletConnectionOutcome
import xyz.lilsus.rayl.providers.nwc.feature.walletconnection.AddNwcWalletEvent
import xyz.lilsus.rayl.providers.nwc.feature.walletconnection.AddNwcWalletViewModel
import xyz.lilsus.rayl.providers.nwc.feature.walletconnection.ConnectNwcWalletEvent
import xyz.lilsus.rayl.providers.nwc.feature.walletconnection.ConnectNwcWalletViewModel
import xyz.lilsus.rayl.providers.nwc.feature.walletconnection.nativeNwcActiveEncryptionText
import xyz.lilsus.rayl.providers.nwc.feature.walletconnection.nativeNwcWalletConnectionText
import xyz.lilsus.rayl.providers.nwc.integration.nwc.NwcConnectionError
import xyz.lilsus.rayl.providers.nwc.integration.nwc.NwcWalletDiscovery
import xyz.lilsus.raylsuite.core.camera.CameraAuthorizationState
import xyz.lilsus.raylsuite.core.camera.QrScannerController
import xyz.lilsus.raylsuite.core.camera.createNativeQrScannerController
import xyz.lilsus.raylsuite.core.camera.nativeCameraAuthorizationState
import xyz.lilsus.raylsuite.core.camera.requestNativeCameraPermission
import xyz.lilsus.raylsuite.core.ui.NativeCredentialText
import xyz.lilsus.raylsuite.core.ui.nativeCredentialText
import xyz.lilsus.raylsuite.core.ui.platform.CredentialClipboard
import xyz.lilsus.raylsuite.feature.onboarding.nativeOnboardingText

data class NwcNativeOnboardingSnapshot(
    val step: String,
    val credentialText: NativeCredentialText,
    val privacyPolicyUrl: String?,
    val termsUrl: String?,
    val settingsFlow: Boolean,
    val connectionOnly: Boolean,
    val stepIndex: Int,
    val stepCount: Int,
    val backTitle: String,
    val agreementTitle: String,
    val agreementBody: String,
    val agreementCheckboxTitle: String,
    val agreementNextTitle: String,
    val hasAgreed: Boolean,
    val instructionsTitle: String,
    val instructionsIntro: String,
    val instructionSteps: List<String>,
    val connectWalletTitle: String,
    val addTitle: String,
    val addDescription: String,
    val uriLabel: String,
    val uriPlaceholder: String,
    val pasteTitle: String,
    val scanInstruction: String,
    val scanPermission: String,
    val scanAllowCamera: String,
    val scanOpenSettings: String,
    val scanRestricted: String,
    val scanUnavailable: String,
    val uri: String,
    val canSubmitUri: Boolean,
    val uriError: String?,
    val cameraAuthorization: String,
    val scannerUnavailable: Boolean,
    val confirmationPresented: Boolean,
    val confirmTitle: String,
    val confirmDescription: String,
    val cancelTitle: String,
    val confirmActionTitle: String,
    val retryTitle: String,
    val aliasLabel: String,
    val alias: String,
    val discoveryLoading: Boolean,
    val discoveryLoadingTitle: String,
    val saving: Boolean,
    val canConfirm: Boolean,
    val connectionError: String?,
    val warningHeading: String,
    val warnings: List<String>,
    val publicKeyLabel: String,
    val walletPublicKey: String?,
    val relayLabel: String,
    val relay: String?,
    val lightningAddressLabel: String,
    val lightningAddress: String?,
    val methodsLabel: String,
    val methods: String?,
    val encryptionLabel: String,
    val encryptionSchemes: String?,
    val activeEncryption: String?
)

class NwcNativeOnboardingController internal constructor(private val runtime: NwcRuntime) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val addWallet = AddNwcWalletViewModel()
    private val clipboard = CredentialClipboard()
    private val connectWallet = ConnectNwcWalletViewModel(runtime.nwcWallet)
    private val scanner: QrScannerController = createNativeQrScannerController()
    private val snapshot = MutableStateFlow<NwcNativeOnboardingSnapshot?>(null)
    private val observers = mutableSetOf<(NwcNativeOnboardingSnapshot) -> Unit>()
    private val connectionOnly = runtime.onboardingState.completed.value
    private var currentStep =
        if (runtime.nwcWallet.connection.value != null &&
            !connectionOnly
        ) {
            STEP_AGREEMENT
        } else {
            STEP_WALLET
        }
    private var confirmationPresented = false
    private var scannerActive = false
    private var scannerStarted = false
    private var scannerUnavailable = false
    private var settingsFlow = false

    init {
        scope.launch {
            combine(
                runtime.onboardingViewModel.uiState,
                addWallet.uiState,
                connectWallet.uiState,
                runtime.languageRepository.preference
            ) { _, _, _, _ -> Unit }.collect { publishSnapshot() }
        }
        scope.launch {
            addWallet.events.collect { event ->
                when (event) {
                    is AddNwcWalletEvent.Confirm -> beginConfirmation(event.uri)
                }
            }
        }
        scope.launch {
            connectWallet.events.collect { event ->
                when (event) {
                    is ConnectNwcWalletEvent.Success -> {
                        clipboard.clearAfterSaving()
                        addWallet.reset()
                        clearCredentialSnapshot()
                        confirmationPresented = false
                        runtime.connectionDraft.clear()
                        when (
                            nwcWalletConnectionOutcome(
                                fromSettings = settingsFlow,
                                hasAgreed = connectionOnly ||
                                    runtime.onboardingViewModel.uiState.value.hasAgreed
                            )
                        ) {
                            NwcWalletConnectionOutcome.ResumeOnboarding ->
                                moveTo(STEP_AGREEMENT)

                            NwcWalletConnectionOutcome.CompleteOnboarding ->
                                runtime.completeOnboarding()

                            NwcWalletConnectionOutcome.FinishSettings -> {
                                settingsFlow = false
                                runtime.walletFlowHandled()
                            }
                        }
                        stopScanner()
                        publishSnapshot()
                    }

                    ConnectNwcWalletEvent.Cancelled -> {
                        clipboard.discard()
                        addWallet.reset()
                        clearCredentialSnapshot()
                        confirmationPresented = false
                        runtime.connectionDraft.clear()
                        publishSnapshot()
                        reconcileScanner()
                    }
                }
            }
        }
        scope.launch {
            runtime.onboardingWalletFlow.collect { requested ->
                if (!requested) return@collect
                val uri = runtime.connectionDraft.uri
                runtime.walletFlowHandled()
                uri?.let(::beginConfirmation)
            }
        }
    }

    fun clear() {
        stopScanner()
        addWallet.clear()
        connectWallet.clear()
        runtime.connectionDraft.clear()
        clipboard.discard()
        clearCredentialSnapshot()
        observers.clear()
        scope.coroutineContext[Job]?.cancel()
    }

    fun observe(onChange: (NwcNativeOnboardingSnapshot) -> Unit): () -> Unit {
        if (!scope.isActive) {
            snapshot.value?.let(onChange)
            return {}
        }
        observers += onChange
        val job = scope.launch { snapshot.filterNotNull().collect(onChange) }
        return {
            observers -= onChange
            job.cancel()
        }
    }

    fun setAgreement(agreed: Boolean) {
        runtime.onboardingViewModel.setAgreement(agreed)
    }

    fun continueAgreement() {
        if (!runtime.onboardingViewModel.uiState.value.hasAgreed) return

        if (runtime.nwcWallet.connection.value == null) {
            moveTo(STEP_WALLET)
        } else {
            runtime.completeOnboarding()
        }
    }

    fun showWalletConnection() {
        if (!runtime.canAcceptWalletInput) return
        moveTo(STEP_WALLET)
    }

    fun showWalletInstructions() {
        if (confirmationPresented) return
        moveTo(STEP_INSTRUCTIONS)
    }

    fun updateUri(uri: String) {
        if (confirmationPresented) return
        clipboard.retainFor(uri)
        addWallet.updateUri(uri)
    }

    fun pasteUri() {
        if (confirmationPresented) return
        addWallet.prefillUriIfValid(clipboard.read())
    }

    fun submitUri() {
        if (!runtime.canAcceptWalletInput) return
        addWallet.submit()
    }

    fun updateAlias(alias: String) {
        connectWallet.updateAlias(alias)
    }

    fun retryDiscovery() {
        if (!runtime.canAcceptWalletInput) return
        connectWallet.retryDiscovery()
    }

    fun confirmConnection() {
        if (!runtime.canAcceptWalletInput) return
        connectWallet.confirm()
    }

    fun cancelConnection() {
        connectWallet.cancel()
    }

    fun setScannerActive(active: Boolean) {
        scannerActive = active
        reconcileScanner()
        scope.launch { publishSnapshot() }
    }

    fun requestCameraAccess() {
        requestNativeCameraPermission {
            scope.launch {
                publishSnapshot()
                reconcileScanner()
            }
        }
    }

    fun startSettingsWalletFlow() {
        if (!runtime.canAcceptWalletInput) return
        settingsFlow = true
        currentStep = STEP_WALLET
        runtime.connectionDraft.uri?.let {
            beginConfirmation(it)
            return
        }
        scope.launch {
            publishSnapshot()
            reconcileScanner()
        }
    }

    fun finishSettingsWalletFlow() {
        resetConnectionInput()
        settingsFlow = false
        confirmationPresented = false
        runtime.connectionDraft.clear()
        runtime.walletFlowHandled()
        stopScanner()
        currentStep =
            if (runtime.nwcWallet.connection.value != null &&
                !connectionOnly
            ) {
                STEP_AGREEMENT
            } else {
                STEP_WALLET
            }
        scope.launch { publishSnapshot() }
    }

    fun back() {
        when (currentStep) {
            STEP_INSTRUCTIONS -> moveTo(STEP_WALLET)
        }
    }

    private fun moveTo(step: String) {
        currentStep = step
        if (step != STEP_WALLET) {
            stopScanner()
            resetConnectionInput()
        }
        scope.launch {
            publishSnapshot()
            reconcileScanner()
        }
    }

    private fun beginConfirmation(uri: String) {
        if (!runtime.canAcceptWalletInput) return
        clipboard.retainFor(uri)
        addWallet.reset()
        runtime.connectionDraft.set(uri)
        confirmationPresented = true
        stopScanner()
        connectWallet.load(uri)
        scope.launch { publishSnapshot() }
    }

    private fun resetConnectionInput() {
        addWallet.reset()
        connectWallet.reset()
        runtime.connectionDraft.clear()
        clipboard.discard()
        clearCredentialSnapshot()
    }

    private fun clearCredentialSnapshot() {
        val cleared = snapshot.value?.copy(
            uri = "",
            uriError = null,
            canSubmitUri = false,
            alias = "",
            walletPublicKey = null,
            relay = null,
            lightningAddress = null,
            methods = null,
            encryptionSchemes = null,
            activeEncryption = null,
            warnings = emptyList(),
            connectionError = null,
            confirmationPresented = false,
            discoveryLoading = false,
            saving = false,
            canConfirm = false
        ) ?: return
        snapshot.value = cleared
        // Disposal can cancel Flow collection before Swift receives the cleared snapshot.
        observers.toList().forEach { it(cleared) }
    }

    private fun reconcileScanner() {
        val shouldRun =
            scannerActive && currentStep == STEP_WALLET && !confirmationPresented
        if (
            !shouldRun ||
            nativeCameraAuthorizationState() != CameraAuthorizationState.AUTHORIZED
        ) {
            if (scannerStarted) scanner.stop()
            scannerStarted = false
            return
        }
        if (scannerStarted) return
        scannerUnavailable = false
        scannerStarted =
            scanner.start(
                onQrCodeScanned = {
                    clipboard.discard()
                    addWallet.handleScannedValue(it)
                },
                onCameraPermissionMissing = {
                    scannerStarted = false
                    scope.launch { publishSnapshot() }
                },
                onScannerUnavailable = {
                    scannerStarted = false
                    scannerUnavailable = true
                    scope.launch { publishSnapshot() }
                }
            )
    }

    private fun stopScanner() {
        if (scannerStarted) scanner.stop()
        scannerStarted = false
    }

    private suspend fun publishSnapshot() {
        val state = runtime.onboardingViewModel.uiState.value
        val add = addWallet.uiState.value
        val connect = connectWallet.uiState.value
        val common = nativeOnboardingText()
        val nwc = nativeNwcOnboardingText(runtime.configuration.appName)
        val wallet = nativeNwcWalletConnectionText(runtime.configuration.appName)
        val discovery = connect.discovery

        snapshot.value =
            NwcNativeOnboardingSnapshot(
                step = currentStep,
                credentialText = nativeCredentialText(),
                privacyPolicyUrl = runtime.configuration.legalLinks.privacyPolicyUrl,
                termsUrl = runtime.configuration.legalLinks.termsUrl,
                settingsFlow = settingsFlow,
                connectionOnly = connectionOnly,
                stepIndex = 0,
                stepCount = 0,
                backTitle = common.back,
                agreementTitle = common.agreementTitle,
                agreementBody = nwc.agreementBody,
                agreementCheckboxTitle = common.agreementCheckbox,
                agreementNextTitle = common.agreementContinue,
                hasAgreed = state.hasAgreed,
                instructionsTitle = nwc.instructionsTitle,
                instructionsIntro = nwc.instructionsIntro,
                instructionSteps = nwc.instructionSteps,
                connectWalletTitle = wallet.addTitle,
                addTitle = wallet.addTitle,
                addDescription = wallet.addDescription,
                uriLabel = wallet.uriLabel,
                uriPlaceholder = wallet.uriPlaceholder,
                pasteTitle = wallet.paste,
                scanInstruction = wallet.scanInstruction,
                scanPermission = wallet.scanPermission,
                scanAllowCamera = wallet.scanAllowCamera,
                scanOpenSettings = wallet.scanOpenSettings,
                scanRestricted = wallet.scanRestricted,
                scanUnavailable = wallet.scanUnavailable,
                uri = add.uri,
                canSubmitUri = add.isUriValid,
                uriError = add.error?.message(wallet),
                cameraAuthorization = nativeCameraAuthorizationState().nativeValue(),
                scannerUnavailable = scannerUnavailable,
                confirmationPresented = confirmationPresented,
                confirmTitle = wallet.confirmTitle,
                confirmDescription = wallet.confirmDescription,
                cancelTitle = wallet.cancel,
                confirmActionTitle = wallet.confirm,
                retryTitle = wallet.retry,
                aliasLabel = wallet.aliasLabel,
                alias = connect.alias,
                discoveryLoading = connect.isDiscoveryLoading,
                discoveryLoadingTitle = wallet.loading,
                saving = connect.isSaving,
                canConfirm =
                    discovery?.supportsRequiredMethods == true &&
                        !connect.isSaving &&
                        !connect.isDiscoveryLoading,
                connectionError = connect.error?.message(wallet),
                warningHeading = wallet.warningHeading,
                warnings = discovery?.warnings(wallet).orEmpty(),
                publicKeyLabel = wallet.publicKeyLabel,
                walletPublicKey = discovery?.walletPublicKey,
                relayLabel = wallet.relayLabel,
                relay = discovery?.relayUrl,
                lightningAddressLabel = wallet.lightningAddressLabel,
                lightningAddress = discovery?.lightningAddress,
                methodsLabel = wallet.methodsLabel,
                methods = discovery?.metadata?.methods?.sorted()?.joinToString(),
                encryptionLabel = wallet.encryptionLabel,
                encryptionSchemes =
                    discovery?.metadata?.encryptionSchemes?.sorted()?.joinToString(),
                activeEncryption =
                    discovery?.metadata?.negotiatedEncryption?.let {
                        nativeNwcActiveEncryptionText(it)
                    }
            )
    }

    private companion object {
        const val STEP_AGREEMENT = "agreement"
        const val STEP_INSTRUCTIONS = "instructions"
        const val STEP_WALLET = "wallet"
    }
}

private fun CameraAuthorizationState.nativeValue(): String = when (this) {
    CameraAuthorizationState.NOT_DETERMINED -> "notDetermined"
    CameraAuthorizationState.AUTHORIZED -> "authorized"
    CameraAuthorizationState.DENIED -> "denied"
    CameraAuthorizationState.RESTRICTED -> "restricted"
    CameraAuthorizationState.UNAVAILABLE -> "unavailable"
}

private fun NwcConnectionError.message(
    text: xyz.lilsus.rayl.providers.nwc.feature.walletconnection.NativeNwcWalletConnectionText
): String = when (this) {
    NwcConnectionError.AlreadyConnected -> text.errorAlreadyConnected
    NwcConnectionError.InvalidUri -> text.errorInvalidUri
    NwcConnectionError.RequiredMethodsMissing -> text.requiredMethods
    is NwcConnectionError.ConnectionFailed -> text.errorConnection
}

private fun NwcWalletDiscovery.warnings(
    text: xyz.lilsus.rayl.providers.nwc.feature.walletconnection.NativeNwcWalletConnectionText
): List<String> = buildList {
    if (!supportsRequiredMethods) add(text.requiredMethods)
    if (!supportsPayInvoice) add(text.warningMissingPayInvoice)
    if (!supportsLookupInvoice) add(text.warningMissingLookupInvoice)
    when {
        usesLegacyEncryption && metadata.encryptionDefaultedToNip04 ->
            add(text.warningLegacyNip04Default)

        usesLegacyEncryption -> add(text.warningLegacyNip04)

        !supportsNip44 -> add(text.warningMissingNip44)
    }
}
