import CoreImage.CIFilterBuiltins
import SwiftUI
import Shared

private func raylShellStage(_ value: String) -> NativeShellStage {
    switch value {
    case "tabs": return .tabs
    case "problem": return .onboarding
    default: return .loading
    }
}

private func makeRaylShellAdapter(_ experience: RaylIosExperience) -> NativeShellAdapter {
    NativeShellAdapter(
        initialStage: { raylShellStage(experience.stage()) },
        initialSelectedTab: experience.selectedTab,
        selectTab: { experience.selectTab(tab: $0) },
        observeStage: { observer in
            experience.observeStage { observer.send(raylShellStage($0)) }
        },
        observeSelectedTab: { observer in
            experience.observeSelectedTab(onChange: observer.send)
        },
        observeTheme: { observer in
            experience.observeTheme(onChange: observer.send)
        },
        observeTabTitles: { observer in
            experience.observeTabTitles(onChange: observer.send)
        },
        nativeScanView: {
            AnyView(NativePaymentScanView(
                controller: experience.scanController(),
                recentController: experience.recentController()
            ))
        },
        nativeHubView: {
            AnyView(NativePaymentHubView(controller: experience.hubController()))
        },
        nativeSettingsView: {
            AnyView(
                NativeSettingsView(
                    controller: experience.settingsController(),
                    trailingContent: AnyView(
                        RaylWalletSettingsActions(controller: experience.walletSettingsController())
                    )
                )
            )
        },
        // Rayl has no setup; this stage only shows a failed "Use another wallet".
        nativeOnboardingView: {
            AnyView(RaylProblemView(experience: experience))
        }
    )
}

struct RaylExperienceView: View {
    private let adapter: NativeShellAdapter

    init(experience: RaylIosExperience) {
        adapter = makeRaylShellAdapter(experience)
    }

    var body: some View {
        NativeAppShell(adapter: adapter)
    }
}

@MainActor
private final class RaylProblemModel: ObservableObject {
    @Published private(set) var snapshot: RaylProblemSnapshot?
    private var cancel: (() -> Void)?

    init(experience: RaylIosExperience) {
        cancel = experience.observeProblem { [weak self] in self?.snapshot = $0 }
    }

    deinit { cancel?() }
}

private struct RaylProblemView: View {
    let experience: RaylIosExperience
    @StateObject private var model: RaylProblemModel

    init(experience: RaylIosExperience) {
        self.experience = experience
        _model = StateObject(wrappedValue: RaylProblemModel(experience: experience))
    }

    var body: some View {
        VStack(spacing: 16) {
            if let snapshot = model.snapshot {
                Text(snapshot.message)
                    .multilineTextAlignment(.center)
                Button(snapshot.retryTitle) { experience.retry() }
                    .buttonStyle(.borderedProminent)
            } else {
                ProgressView()
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

@MainActor
private final class RaylWalletSettingsModel: ObservableObject {
    @Published private(set) var snapshot: RaylNativeWalletSettingsSnapshot?

    let controller: RaylNativeWalletSettingsController
    private var cancel: (() -> Void)?

    init(controller: RaylNativeWalletSettingsController) {
        self.controller = controller
        cancel = controller.observe { [weak self] in self?.snapshot = $0 }
    }

    deinit { cancel?() }
}

private struct RaylWalletSettingsActions: View {
    @StateObject private var model: RaylWalletSettingsModel
    @State private var confirmsErase = false

    init(controller: RaylNativeWalletSettingsController) {
        _model = StateObject(wrappedValue: RaylWalletSettingsModel(controller: controller))
    }

    var body: some View {
        if let snapshot = model.snapshot {
            VStack(alignment: .leading, spacing: 12) {
                if snapshot.pocketFailed {
                    Button { model.controller.retryPocket() } label: { balance(snapshot) }
                } else {
                    Button { model.controller.addFunds() } label: { balance(snapshot) }
                        .disabled(snapshot.fundingStage == "creating")
                        .accessibilityIdentifier("rayl_balance")
                        .alert(
                            snapshot.invoiceFailed,
                            isPresented: Binding(
                                get: { model.snapshot?.fundingStage == "failed" },
                                set: { if !$0 { model.controller.resetFunding() } }
                            )
                        ) {
                            Button(snapshot.doneTitle) { model.controller.resetFunding() }
                        }
                }
                Divider()
                NavigationLink {
                    RaylUseAnotherWalletView(model: model)
                } label: {
                    detail(
                        snapshot.useAnotherWalletTitle,
                        snapshot.useAnotherWalletBody,
                        systemImage: "arrow.left.arrow.right"
                    )
                }
                .disabled(snapshot.isSubmitting)
                if snapshot.canErase {
                    Divider()
                    Button(role: .destructive) {
                        confirmsErase = true
                    } label: {
                        detail(snapshot.eraseTitle, snapshot.eraseBody, systemImage: "trash")
                    }
                    .disabled(snapshot.isSubmitting)
                }
            }
            .onChange(of: snapshot.fundingStage, initial: true) { _, stage in
                if stage == "open", let invoice = model.snapshot?.invoice {
                    openInWallet(invoice)
                }
            }
            .sheet(
                isPresented: Binding(
                    get: { ["shown", "received"].contains(model.snapshot?.fundingStage ?? "") },
                    set: { if !$0 { model.controller.resetFunding() } }
                )
            ) {
                RaylFundingSheet(model: model)
            }
            .alert(snapshot.eraseTitle, isPresented: $confirmsErase) {
                Button(snapshot.cancelTitle, role: .cancel) {}
                Button(snapshot.eraseConfirmTitle, role: .destructive) {
                    model.controller.erasePocket()
                }
            } message: {
                Text(snapshot.eraseBody)
            }
        } else {
            ProgressView()
        }
    }

    private func balance(_ snapshot: RaylNativeWalletSettingsSnapshot) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Label(snapshot.balanceTitle, systemImage: "creditcard")
                Text(snapshot.balance)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if snapshot.fundingStage == "creating" {
                ProgressView()
            } else if !snapshot.pocketFailed {
                Text(snapshot.addFundsTitle)
                    .foregroundStyle(.tint)
            }
        }
        .contentShape(Rectangle())
    }

    /// Hands the amountless invoice to another wallet app, where the user chooses the amount.
    /// Rayl handles `lightning:` links too; if iOS sends the link back, Rayl shows the invoice.
    private func openInWallet(_ invoice: String) {
        guard let url = URL(string: "lightning:\(invoice)") else {
            model.controller.showInvoice()
            return
        }
        UIApplication.shared.open(url) { opened in
            if opened {
                model.controller.openedInWallet()
            } else {
                model.controller.showInvoice()
            }
        }
    }

    private func detail(_ title: String, _ body: String, systemImage: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(title, systemImage: systemImage)
            Text(body)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }
}

/// The entry point to external wallets; choosing one closes Rayl's wallet and starts its setup.
private struct RaylUseAnotherWalletView: View {
    @ObservedObject var model: RaylWalletSettingsModel

    var body: some View {
        List {
            if let snapshot = model.snapshot {
                Section {
                    ForEach(snapshot.otherWallets, id: \.id) { wallet in
                        Button {
                            model.controller.useWallet(id: wallet.id)
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(wallet.title)
                                        .foregroundStyle(.primary)
                                    Text(wallet.subtitle)
                                        .font(.footnote)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(.tertiary)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(snapshot.isSubmitting)
                        .accessibilityIdentifier("rayl_use_wallet_\(wallet.id)")
                    }
                } footer: {
                    Text(snapshot.useAnotherWalletInfo)
                }
            }
        }
        .navigationTitle(model.snapshot?.useAnotherWalletTitle ?? "")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// The funding invoice when no other wallet app opened it, then the amount that arrived.
private struct RaylFundingSheet: View {
    @ObservedObject var model: RaylWalletSettingsModel

    var body: some View {
        ScrollView {
            if let snapshot = model.snapshot {
                content(snapshot)
                    .padding(24)
                    .frame(maxWidth: 520)
                    .frame(maxWidth: .infinity)
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    @ViewBuilder
    private func content(_ snapshot: RaylNativeWalletSettingsSnapshot) -> some View {
        VStack(spacing: 20) {
            if snapshot.fundingStage == "received" {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 64))
                    .foregroundStyle(.tint)
                Text(snapshot.fundsAdded)
                    .font(.title2.bold())
                if let amount = snapshot.fundsAddedAmount {
                    Text(amount)
                        .font(.headline)
                        .foregroundStyle(.secondary)
                }
                Button(snapshot.doneTitle) { model.controller.resetFunding() }
                    .buttonStyle(.borderedProminent)
            } else if let invoice = snapshot.invoice {
                Text(snapshot.addFundsTitle)
                    .font(.title2.bold())
                Text(snapshot.addFundsBody)
                    .multilineTextAlignment(.center)
                RaylQRCode(value: invoice.uppercased())
                Text(invoice)
                    .font(.footnote.monospaced())
                    .lineLimit(2)
                    .truncationMode(.middle)
                Button(snapshot.copyInvoiceTitle) { UIPasteboard.general.string = invoice }
                    .buttonStyle(.bordered)
                Text(snapshot.waitingForPayment)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

private struct RaylQRCode: View {
    let value: String

    var body: some View {
        if let image = Self.render(value) {
            Image(uiImage: image)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .frame(width: 240, height: 240)
                .accessibilityHidden(true)
        }
    }

    private static func render(_ value: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(value.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let image = CIContext().createCGImage(output, from: output.extent)
        else { return nil }
        return UIImage(cgImage: image)
    }
}
