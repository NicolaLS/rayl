import Shared
import SwiftUI
import UIKit

@MainActor
private final class NwcNativeOnboardingModel: ObservableObject {
    @Published private(set) var snapshot: NwcNativeOnboardingSnapshot?

    let controller: NwcNativeOnboardingController
    private var cancel: (() -> Void)?

    init(controller: NwcNativeOnboardingController) {
        self.controller = controller
        cancel = controller.observe { [weak self] snapshot in
            self?.snapshot = snapshot
        }
    }

    deinit {
        cancel?()
    }
}

struct NwcNativeOnboardingView: View {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var model: NwcNativeOnboardingModel

    init(controller: NwcNativeOnboardingController) {
        _model = StateObject(wrappedValue: NwcNativeOnboardingModel(controller: controller))
    }

    var body: some View {
        NavigationStack {
            Group {
                if let snapshot = model.snapshot {
                    screen(snapshot)
                        .toolbar {
                            if snapshot.settingsFlow || snapshot.step == "instructions" {
                                ToolbarItem(placement: .topBarLeading) {
                                    Button(
                                        action: snapshot.settingsFlow
                                            ? model.controller.finishSettingsWalletFlow
                                            : model.controller.back
                                    ) {
                                        Label(
                                            snapshot.settingsFlow ? snapshot.cancelTitle : snapshot.backTitle,
                                            systemImage: snapshot.settingsFlow ? "xmark" : "chevron.left"
                                        )
                                            .labelStyle(.iconOnly)
                                    }
                                }
                            }
                        }
                        .sheet(isPresented: confirmationPresented(snapshot)) {
                            confirmation(snapshot)
                                .interactiveDismissDisabled(snapshot.saving)
                        }
                } else {
                    ProgressView()
                }
            }
        }
        .onChange(of: scenePhase) { _, phase in
            updateScannerActivity(phase: phase)
        }
    }

    @ViewBuilder
    private func screen(_ snapshot: NwcNativeOnboardingSnapshot) -> some View {
        switch snapshot.step {
        case "agreement":
            NativeOnboardingAgreementView(
                title: snapshot.agreementTitle,
                body: snapshot.agreementBody,
                checkboxTitle: snapshot.agreementCheckboxTitle,
                actionTitle: snapshot.agreementNextTitle,
                hasAgreed: snapshot.hasAgreed,
                stepIndex: snapshot.stepIndex,
                stepCount: snapshot.stepCount,
                onAgreementChanged: model.controller.setAgreement,
                action: model.controller.continueAgreement
            )
        case "instructions":
            instructions(snapshot)
        case "wallet":
            wallet(snapshot)
        default:
            ProgressView()
        }
    }

    private func instructions(_ snapshot: NwcNativeOnboardingSnapshot) -> some View {
        NativeOnboardingProgressLayout(
            stepIndex: 0,
            stepCount: 0
        ) {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text(snapshot.instructionsTitle)
                        .font(.title2.bold())
                    Text(snapshot.instructionsIntro)
                        .foregroundStyle(.secondary)

                    ForEach(Array(snapshot.instructionSteps.enumerated()), id: \.offset) {
                        index,
                        step in
                        HStack(alignment: .top, spacing: 12) {
                            Text(String(index + 1))
                                .font(.headline)
                                .foregroundStyle(.white)
                                .frame(width: 32, height: 32)
                                .background(Color.accentColor, in: Circle())
                            Text(step)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            Button(snapshot.connectWalletTitle, action: model.controller.showWalletConnection)
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .frame(maxWidth: .infinity)
        }
    }

    private func wallet(_ snapshot: NwcNativeOnboardingSnapshot) -> some View {
        NativeCredentialConnectionView(
            title: snapshot.addTitle,
            description: snapshot.addDescription,
            actionTitle: snapshot.credentialText.connect,
            canSubmit: snapshot.canSubmitUri,
            isSubmitting: false,
            instructionsTitle: snapshot.credentialText.instructions,
            connectionNotice: snapshot.credentialText.connectionNotice,
            privacyTitle: snapshot.credentialText.privacy,
            privacyPolicyUrl: snapshot.privacyPolicyUrl,
            termsTitle: snapshot.credentialText.terms,
            termsUrl: snapshot.termsUrl,
            onInstructions: model.controller.showWalletInstructions,
            onSubmit: model.controller.submitUri
        ) {
            NativeCredentialInput(
                value: Binding(get: { snapshot.uri }, set: model.controller.updateUri),
                label: snapshot.uriLabel,
                placeholder: snapshot.uriPlaceholder,
                showTitle: snapshot.credentialText.show,
                hideTitle: snapshot.credentialText.hide,
                pasteTitle: snapshot.credentialText.paste,
                onPaste: model.controller.pasteUri,
                onSubmit: {
                    if snapshot.canSubmitUri { model.controller.submitUri() }
                }
            )
            if let error = snapshot.uriError {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .accessibilityAddTraits(.isStaticText)
            }
            scannerCard(snapshot)
        }
        .onAppear {
            model.controller.setScannerActive(active: scenePhase == .active)
        }
        .onDisappear {
            model.controller.setScannerActive(active: false)
        }
    }

    private func scannerCard(_ snapshot: NwcNativeOnboardingSnapshot) -> some View {
        VStack(spacing: 14) {
            Image(systemName: snapshot.cameraAuthorization == "authorized" ? "viewfinder" : "camera.fill")
                .font(.system(size: 48, weight: .light))
                .foregroundStyle(.tint)
                .accessibilityHidden(true)

            Text(
                snapshot.cameraAuthorization == "authorized" && !snapshot.scannerUnavailable
                    ? snapshot.scanInstruction
                    : cameraMessage(snapshot)
            )
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)

            if snapshot.cameraAuthorization == "notDetermined" {
                Button(snapshot.scanAllowCamera, action: model.controller.requestCameraAccess)
                    .buttonStyle(.bordered)
            } else if snapshot.cameraAuthorization == "denied" {
                Button(snapshot.scanOpenSettings, action: openSystemSettings)
                    .buttonStyle(.bordered)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(24)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private func confirmation(_ snapshot: NwcNativeOnboardingSnapshot) -> some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text(snapshot.confirmDescription)
                        .foregroundStyle(.secondary)

                    if snapshot.discoveryLoading {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text(snapshot.discoveryLoadingTitle)
                        }
                    }

                    if !snapshot.warnings.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(snapshot.warningHeading)
                                .font(.headline)
                            ForEach(Array(snapshot.warnings.enumerated()), id: \.offset) {
                                _,
                                warning in
                                Text(warning)
                                    .font(.subheadline)
                            }
                        }
                        .foregroundStyle(.orange)
                        .padding()
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
                    }

                    if snapshot.walletPublicKey != nil {
                        TextField(
                            snapshot.aliasLabel,
                            text: Binding(
                                get: { snapshot.alias },
                                set: model.controller.updateAlias
                            )
                        )
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .disabled(snapshot.saving)
                        .padding()
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))

                        detail(snapshot.publicKeyLabel, snapshot.walletPublicKey)
                        detail(snapshot.relayLabel, snapshot.relay)
                        detail(snapshot.lightningAddressLabel, snapshot.lightningAddress)
                        detail(snapshot.methodsLabel, snapshot.methods)
                        detail(snapshot.encryptionLabel, snapshot.encryptionSchemes)

                        if let activeEncryption = snapshot.activeEncryption {
                            Text(activeEncryption)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }

                    if let error = snapshot.connectionError {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .accessibilityAddTraits(.isStaticText)

                        if !snapshot.discoveryLoading && snapshot.walletPublicKey == nil {
                            Button(snapshot.retryTitle, action: model.controller.retryDiscovery)
                                .buttonStyle(.bordered)
                        }
                    }

                    Button(action: model.controller.confirmConnection) {
                        HStack {
                            if snapshot.saving {
                                ProgressView()
                            }
                            Text(snapshot.confirmActionTitle)
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(!snapshot.canConfirm)
                }
                .padding(24)
            }
            .navigationTitle(snapshot.confirmTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(snapshot.cancelTitle, action: model.controller.cancelConnection)
                        .disabled(snapshot.saving)
                }
            }
        }
    }

    @ViewBuilder
    private func detail(_ title: String, _ value: String?) -> some View {
        if let value, !value.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Text(value)
                    .font(.body)
                    .textSelection(.enabled)
            }
        }
    }

    private func confirmationPresented(
        _ snapshot: NwcNativeOnboardingSnapshot
    ) -> Binding<Bool> {
        Binding(
            get: { snapshot.confirmationPresented },
            set: { presented in
                if !presented {
                    model.controller.cancelConnection()
                }
            }
        )
    }

    private func cameraMessage(_ snapshot: NwcNativeOnboardingSnapshot) -> String {
        if snapshot.scannerUnavailable {
            return snapshot.scanUnavailable
        }
        switch snapshot.cameraAuthorization {
        case "restricted", "unavailable": return snapshot.scanRestricted
        default: return snapshot.scanPermission
        }
    }

    private func openSystemSettings() {
        guard let settings = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(settings)
    }

    private func updateScannerActivity(phase: ScenePhase) {
        guard model.snapshot?.step == "wallet" else { return }
        model.controller.setScannerActive(active: phase == .active)
    }
}
