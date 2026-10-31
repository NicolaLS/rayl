import Shared
import SwiftUI

@MainActor
private final class SparkNativeOnboardingModel: ObservableObject {
    @Published private(set) var snapshot: SparkNativeOnboardingSnapshot?

    let controller: SparkNativeOnboardingController
    private var cancel: (() -> Void)?

    init(controller: SparkNativeOnboardingController) {
        self.controller = controller
        cancel = controller.observe { [weak self] snapshot in
            self?.snapshot = snapshot
        }
    }

    deinit {
        cancel?()
    }
}

struct SparkNativeOnboardingView: View {
    @StateObject private var model: SparkNativeOnboardingModel

    init(controller: SparkNativeOnboardingController) {
        _model = StateObject(wrappedValue: SparkNativeOnboardingModel(controller: controller))
    }

    var body: some View {
        NavigationStack {
            Group {
                if let snapshot = model.snapshot {
                    screen(snapshot)
                        .toolbar {
                            if snapshot.canGoBack {
                                ToolbarItem(placement: .topBarLeading) {
                                    Button(action: model.controller.back) {
                                        Label(snapshot.backTitle, systemImage: "chevron.left")
                                            .labelStyle(.iconOnly)
                                    }
                                }
                            }
                        }
                        .alert(
                            snapshot.removalTitle,
                            isPresented: removalPresented(snapshot)
                        ) {
                            Button(snapshot.cancelTitle, role: .cancel) {
                                model.controller.cancelReset()
                            }
                            Button(snapshot.removalConfirmTitle, role: .destructive) {
                                model.controller.confirmReset()
                            }
                        } message: {
                            Text(snapshot.removalBody)
                        }
                } else {
                    ProgressView()
                }
            }
        }
    }

    @ViewBuilder
    private func screen(_ snapshot: SparkNativeOnboardingSnapshot) -> some View {
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

    private func instructions(_ snapshot: SparkNativeOnboardingSnapshot) -> some View {
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

    @ViewBuilder
    private func wallet(_ snapshot: SparkNativeOnboardingSnapshot) -> some View {
        if snapshot.walletKind == "import" || snapshot.walletKind == "connecting" {
            NativeCredentialConnectionView(
                title: snapshot.walletTitle,
                description: snapshot.walletBody,
                actionTitle: snapshot.importTitle,
                canSubmit: snapshot.canImport,
                isSubmitting: snapshot.walletKind == "connecting",
                instructionsTitle: snapshot.credentialText.instructions,
                connectionNotice: snapshot.credentialText.connectionNotice,
                privacyTitle: snapshot.credentialText.privacy,
                privacyPolicyUrl: snapshot.privacyPolicyUrl,
                termsTitle: snapshot.credentialText.terms,
                termsUrl: snapshot.termsUrl,
                onInstructions: model.controller.showWalletInstructions,
                onSubmit: model.controller.importWallet
            ) {
                NativeCredentialInput(
                    value: Binding(
                        get: { snapshot.recoveryPhrase },
                        set: model.controller.updateRecoveryPhrase
                    ),
                    label: snapshot.phraseLabel,
                    placeholder: snapshot.phraseHint,
                    showTitle: snapshot.credentialText.show,
                    hideTitle: snapshot.credentialText.hide,
                    pasteTitle: snapshot.credentialText.paste,
                    enabled: snapshot.walletKind == "import",
                    multiline: true,
                    onPaste: model.controller.pasteRecoveryPhrase,
                    onSubmit: {
                        if snapshot.canImport { model.controller.importWallet() }
                    }
                )
                Text(snapshot.storageNote)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                if let error = snapshot.walletError {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .accessibilityAddTraits(.isStaticText)
                }
            }
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    if snapshot.walletKind == "recovery" {
                        recovery(snapshot)
                    } else {
                        progress(snapshot)
                    }
                }
                .padding(24)
                .frame(maxWidth: 608, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle(snapshot.walletTitle)
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    @ViewBuilder
    private func recovery(_ snapshot: SparkNativeOnboardingSnapshot) -> some View {
        Text(snapshot.walletTitle)
            .font(.title2.bold())
        Text(snapshot.walletBody)
            .foregroundStyle(.secondary)

        if let error = snapshot.walletError {
            Text(error)
                .font(.footnote)
                .foregroundStyle(.red)
        }

        Button(snapshot.retryTitle, action: model.controller.retryWallet)
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .frame(maxWidth: .infinity)

        Button(snapshot.resetTitle, role: .destructive, action: model.controller.requestReset)
            .buttonStyle(.bordered)
            .controlSize(.large)
            .frame(maxWidth: .infinity)
    }

    private func progress(_ snapshot: SparkNativeOnboardingSnapshot) -> some View {
        VStack(spacing: 16) {
            ProgressView()
            if let status = snapshot.walletStatus {
                Text(status)
                    .font(.headline)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 240)
    }

    private func removalPresented(
        _ snapshot: SparkNativeOnboardingSnapshot
    ) -> Binding<Bool> {
        Binding(
            get: { snapshot.confirmRemoval },
            set: { presented in
                if !presented {
                    model.controller.cancelReset()
                }
            }
        )
    }

}
