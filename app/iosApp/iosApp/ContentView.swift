import Shared
import SwiftUI

@MainActor
private final class RaylModel: ObservableObject {
    @Published private(set) var snapshot: RaylSnapshot?
    private var cancel: (() -> Void)?
    init() { cancel = RaylIosApp.shared.observe { [weak self] in self?.snapshot = $0 } }
    deinit { cancel?() }
}

struct ContentView: View {
    @StateObject private var model = RaylModel()

    var body: some View {
        Group {
            if let snapshot = model.snapshot {
                VStack(spacing: 0) {
                    if snapshot.preparingWallets {
                        VStack(spacing: 16) {
                            if snapshot.walletCleanupFailed {
                                Text(snapshot.text["wallet_cleanup_failed"] ?? "")
                                Button(snapshot.text["wallet_cleanup_retry"] ?? "") {
                                    RaylIosApp.shared.retryWalletCleanup()
                                }
                            } else {
                                ProgressView()
                            }
                        }
                        .padding(24)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else if snapshot.closingSpark || snapshot.sparkCloseFailed {
                        VStack(spacing: 16) {
                            if snapshot.sparkCloseFailed {
                                Text(snapshot.text["session_close_failed"] ?? "")
                                Button(snapshot.text["session_close_retry"] ?? "") {
                                    RaylIosApp.shared.retrySparkClose()
                                }
                            } else {
                                ProgressView()
                            }
                        }
                        .padding(24)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else if let experience = snapshot.raylExperience {
                        RaylExperienceView(experience: experience)
                            .id(ObjectIdentifier(experience))
                    } else if let experience = snapshot.blinkExperience {
                        BlinkExperienceView(experience: experience)
                            .id(ObjectIdentifier(experience))
                    } else if let experience = snapshot.nwcExperience {
                        NwcExperienceView(experience: experience)
                            .id(ObjectIdentifier(experience))
                    } else if let experience = snapshot.sparkExperience {
                        SparkExperienceView(experience: experience)
                            .id(ObjectIdentifier(experience))
                    } else if !snapshot.onboarded {
                        RaylAppOnboardingView(controller: RaylIosApp.shared.onboardingController())
                    } else {
                        ProgressView()
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
                .id(snapshot.wallet)
                .overlay(alignment: .top) {
                    // Not a question: Rayl already pays with that wallet.
                    if let notice = snapshot.notice {
                        Text(notice)
                            .font(.subheadline)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 12)
                            .background(.regularMaterial, in: Capsule())
                            .padding(.horizontal, 24)
                            .padding(.top, 8)
                            .transition(.move(edge: .top).combined(with: .opacity))
                            .task(id: notice) {
                                try? await Task.sleep(nanoseconds: 4_000_000_000)
                                RaylIosApp.shared.dismissNotice()
                            }
                            .accessibilityIdentifier("card_notice")
                    }
                }
                .animation(.default, value: snapshot.notice)
                .preferredColorScheme(colorScheme(snapshot.colorScheme))
            } else {
                ProgressView()
            }
        }
    }

    private func colorScheme(_ value: String) -> ColorScheme? {
        switch value {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }
}

@MainActor
private final class RaylAppOnboardingModel: ObservableObject {
    @Published private(set) var snapshot: RaylAppOnboardingSnapshot?

    let controller: RaylAppOnboardingController
    private var cancel: (() -> Void)?

    init(controller: RaylAppOnboardingController) {
        self.controller = controller
        cancel = controller.observe { [weak self] in self?.snapshot = $0 }
    }

    deinit { cancel?() }
}

/// Rayl's onboarding before any wallet: the welcome, which says what Rayl does, and Auto Pay.
private struct RaylAppOnboardingView: View {
    /// In the controller's order: scanning, Auto Pay, the balance.
    private static let benefitSymbols = ["qrcode.viewfinder", "bolt.fill", "creditcard"]

    @StateObject private var model: RaylAppOnboardingModel

    init(controller: RaylAppOnboardingController) {
        _model = StateObject(wrappedValue: RaylAppOnboardingModel(controller: controller))
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
    private func screen(_ snapshot: RaylAppOnboardingSnapshot) -> some View {
        switch snapshot.step {
        case "welcome":
            NativeOnboardingWelcomeView(
                title: snapshot.welcomeTitle,
                description: snapshot.welcomeBody,
                benefits: snapshot.benefits.enumerated().map { index, benefit in
                    NativeOnboardingBenefit(
                        id: index,
                        symbol: Self.benefitSymbols[index % Self.benefitSymbols.count],
                        title: benefit.title,
                        body: benefit.body
                    )
                },
                actionTitle: snapshot.getStartedTitle,
                action: model.controller.getStarted
            )
        case "autoPay":
            NativeOnboardingAutoPayView(
                title: snapshot.autoPayTitle,
                body: snapshot.autoPayBody,
                alwaysTitle: snapshot.autoPayAlwaysTitle,
                thresholdTitle: snapshot.autoPayThresholdTitle,
                thresholdLabel: snapshot.autoPayThresholdLabel,
                hint: snapshot.autoPayHint,
                actionTitle: snapshot.autoPayNextTitle,
                confirmationMode: snapshot.confirmationMode,
                thresholdIndex: snapshot.thresholdIndex,
                thresholdStepCount: snapshot.thresholdStepCount,
                onConfirmationModeChanged: model.controller.setConfirmationMode,
                onThresholdIndexChanged: model.controller.setThresholdIndex,
                action: model.controller.continueAutoPay
            )
        default:
            ProgressView()
        }
    }
}
