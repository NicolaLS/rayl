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
    @State private var showsPassphrase = false

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
                RecoveryPhraseGrid(
                    snapshot: snapshot,
                    controller: model.controller,
                    enabled: snapshot.walletKind == "import"
                )
                passphrase(snapshot)
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

    /// The optional BIP-39 passphrase, behind a link since most wallets have none.
    @ViewBuilder
    private func passphrase(_ snapshot: SparkNativeOnboardingSnapshot) -> some View {
        let enabled = snapshot.walletKind == "import"
        if showsPassphrase || !snapshot.passphrase.isEmpty {
            NativeCredentialInput(
                value: Binding(
                    get: { snapshot.passphrase },
                    set: model.controller.updatePassphrase
                ),
                label: snapshot.passphraseLabel,
                placeholder: snapshot.passphraseHint,
                showTitle: snapshot.credentialText.show,
                hideTitle: snapshot.credentialText.hide,
                pasteTitle: snapshot.credentialText.paste,
                enabled: enabled,
                onPaste: model.controller.pastePassphrase,
                onSubmit: {
                    if snapshot.canImport { model.controller.importWallet() }
                }
            )
            Text(snapshot.passphraseNote)
                .font(.footnote)
                .foregroundStyle(.secondary)
        } else {
            Button(snapshot.passphraseToggle) { showsPassphrase = true }
                .disabled(!enabled)
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

/// The recovery phrase as numbered word fields. Words show in plain text and are hidden from
/// screenshots of the app switcher; each field completes to its only possible BIP-39 word on
/// Return or when left, and a pasted phrase fills every field.
private struct RecoveryPhraseGrid: View {
    let snapshot: SparkNativeOnboardingSnapshot
    let controller: SparkNativeOnboardingController
    let enabled: Bool
    @FocusState private var focused: Int?

    private let columns = [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(snapshot.phraseLabel)
                .font(.subheadline.weight(.semibold))
            Picker(snapshot.phraseLabel, selection: Binding(
                get: { snapshot.words.count },
                set: { controller.setWordCount(count: Int32($0)) }
            )) {
                ForEach(Array(snapshot.wordCounts.enumerated()), id: \.offset) { index, count in
                    Text(snapshot.wordCountTitles[index]).tag(count.intValue)
                }
            }
            .pickerStyle(.segmented)
            .disabled(!enabled)
            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(snapshot.words.indices, id: \.self) { index in
                    field(index)
                }
            }
            HStack {
                Spacer()
                Button(action: controller.pasteRecoveryPhrase) {
                    Label(snapshot.credentialText.paste, systemImage: "doc.on.clipboard")
                }
                .disabled(!enabled)
            }
            if snapshot.wrongWords.contains(where: { $0.boolValue }) {
                Text(snapshot.wordsWrong)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
        }
        .onChange(of: focused) { previous, _ in
            if let previous { controller.completeWord(index: Int32(previous)) }
        }
    }

    private func field(_ index: Int) -> some View {
        let wrong = snapshot.wrongWords[index].boolValue && focused != index
        let completion = focused == index ? snapshot.wordCompletions[index] : ""
        return HStack(spacing: 6) {
            Text("\(index + 1).")
                .monospacedDigit()
                .foregroundStyle(.secondary)
            HStack(spacing: 0) {
                TextField("", text: Binding(
                    get: { snapshot.words[index] },
                    set: { controller.updateWord(index: Int32(index), value: $0) }
                ))
                .fixedSize(horizontal: !completion.isEmpty, vertical: false)
                if !completion.isEmpty {
                    Text(completion).foregroundStyle(.tertiary)
                    Spacer(minLength: 0)
                }
            }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(.asciiCapable)
            .focused($focused, equals: index)
            .submitLabel(index == snapshot.words.count - 1 ? .done : .next)
            .onSubmit {
                focused = index + 1 < snapshot.words.count ? index + 1 : nil
            }
            .accessibilityLabel(snapshot.wordLabels[index])
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 44)
        .background(
            Color(uiColor: .secondarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: 12)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(wrong ? Color.red : Color.clear, lineWidth: 1)
        )
        .privacySensitive()
        .disabled(!enabled)
    }
}

