import Shared
import SwiftUI
import UIKit

@MainActor
private final class BlinkNativeOnboardingModel: ObservableObject {
    @Published private(set) var snapshot: BlinkNativeOnboardingSnapshot?

    let controller: BlinkNativeOnboardingController
    private var cancel: (() -> Void)?

    init(controller: BlinkNativeOnboardingController) {
        self.controller = controller
        cancel = controller.observe { [weak self] snapshot in
            self?.snapshot = snapshot
        }
    }

    deinit {
        cancel?()
    }
}

/// Blink's native onboarding. Kotlin owns settings and wallet work; SwiftUI owns presentation.
struct BlinkNativeOnboardingView: View {
    @StateObject private var model: BlinkNativeOnboardingModel

    init(controller: BlinkNativeOnboardingController) {
        _model = StateObject(wrappedValue: BlinkNativeOnboardingModel(controller: controller))
    }

    var body: some View {
        NavigationStack {
            Group {
                if let snapshot = model.snapshot {
                    screen(snapshot)
                        .navigationBarTitleDisplayMode(.inline)
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
                } else {
                    ProgressView()
                }
            }
        }
    }

    @ViewBuilder
    private func screen(_ snapshot: BlinkNativeOnboardingSnapshot) -> some View {
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

    private func instructions(_ snapshot: BlinkNativeOnboardingSnapshot) -> some View {
        NativeOnboardingProgressLayout(
            stepIndex: 0,
            stepCount: 0
        ) {
            VStack(spacing: 10) {
                Text(snapshot.instructionsTitle)
                    .font(.title2.bold())
                    .multilineTextAlignment(.center)
                Text(snapshot.instructionsIntro)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)

                TabView(
                    selection: Binding(
                        get: { snapshot.instructionPage },
                        set: model.controller.setInstructionPage
                    )
                ) {
                    ForEach(
                        Array(snapshot.instructionPages.enumerated()),
                        id: \.offset
                    ) { index, page in
                        instructionPage(page, number: index + 1)
                            .tag(Int32(index))
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))

                HStack {
                    Button(snapshot.previousStepTitle) {
                        model.controller.setInstructionPage(
                            page: snapshot.instructionPage - 1
                        )
                    }
                    .disabled(snapshot.instructionPage == 0)

                    Spacer()
                    Text(snapshot.instructionProgress)
                        .font(.subheadline.weight(.semibold))
                    Spacer()

                    Button(snapshot.nextStepTitle) {
                        model.controller.setInstructionPage(
                            page: snapshot.instructionPage + 1
                        )
                    }
                    .disabled(
                        snapshot.instructionPage >= snapshot.instructionPages.count - 1
                    )
                }
            }

            Link(destination: URL(string: snapshot.dashboardUrl)!) {
                Text(snapshot.dashboardTitle)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)

            Button(action: model.controller.showWalletConnection) {
                Text(snapshot.enterKeyTitle)
                    .frame(maxWidth: .infinity, minHeight: 28)
            }
            .buttonStyle(.bordered)
            .buttonBorderShape(.roundedRectangle(radius: 16))
            .controlSize(.large)
        }
    }

    private func instructionPage(
        _ page: BlinkNativeOnboardingPage,
        number: Int
    ) -> some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                Text(String(number))
                    .font(.headline)
                    .foregroundStyle(Color.white)
                    .frame(width: 32, height: 32)
                    .background(Color.accentColor, in: Circle())
                Text(page.title)
                    .font(.headline)
                Spacer()
            }

            if let imageName = page.imageName,
                let image = UIImage(named: imageName)
            {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxHeight: 260)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }

            Text(page.body)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18))
        .padding(.horizontal, 2)
    }

    private func wallet(_ snapshot: BlinkNativeOnboardingSnapshot) -> some View {
        NativeCredentialConnectionView(
            title: snapshot.walletTitle,
            description: snapshot.walletDescription,
            actionTitle: snapshot.connectTitle,
            canSubmit: snapshot.canConnect,
            isSubmitting: snapshot.isConnecting,
            instructionsTitle: snapshot.walletInstructionsTitle,
            connectionNotice: snapshot.connectionNotice,
            privacyTitle: snapshot.privacyTitle,
            privacyPolicyUrl: snapshot.privacyPolicyUrl,
            termsTitle: snapshot.termsTitle,
            termsUrl: snapshot.termsUrl,
            onInstructions: model.controller.showWalletInstructions,
            onSubmit: model.controller.connectWallet
        ) {
            NativeCredentialInput(
                value: apiKeyBinding(snapshot),
                label: snapshot.apiKeyLabel,
                placeholder: snapshot.apiKeyPlaceholder,
                showTitle: snapshot.showApiKeyTitle,
                hideTitle: snapshot.hideApiKeyTitle,
                pasteTitle: snapshot.pasteTitle,
                enabled: !snapshot.isConnecting,
                onPaste: model.controller.pasteApiKey,
                onSubmit: {
                    if snapshot.canConnect { model.controller.connectWallet() }
                }
            )
            if let error = snapshot.connectionError {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
        }
    }

    private func apiKeyBinding(_ snapshot: BlinkNativeOnboardingSnapshot) -> Binding<String> {
        Binding(
            get: { snapshot.apiKey },
            set: { model.controller.updateApiKey(apiKey: $0) }
        )
    }

}
