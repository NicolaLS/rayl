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
                    if snapshot.canCancelSetup {
                        HStack {
                            Button(snapshot.text["cancel_setup"] ?? "") { RaylIosApp.shared.cancelSetup() }
                            Spacer()
                        }.padding()
                    }
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
                    } else if !snapshot.welcomeCompleted {
                        NativeOnboardingWelcomeView(
                            title: snapshot.text["welcome_title"] ?? "",
                            subtitle: "",
                            description: snapshot.text["welcome_body"] ?? "",
                            actionTitle: snapshot.text["get_started"] ?? "",
                            action: { RaylIosApp.shared.completeWelcome() }
                        )
                    } else {
                        ProgressView()
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
                .id(snapshot.wallet)
                .preferredColorScheme(colorScheme(snapshot.colorScheme))
                .background {
                    // Keep host messages off the provider's alert presentation branch.
                    Color.clear.alert(
                        "Rayl",
                        isPresented: Binding(
                            get: { snapshot.message != nil },
                            set: { if !$0 { RaylIosApp.shared.dismissMessage() } })
                    ) {
                        Button(snapshot.text["close"] ?? "") { RaylIosApp.shared.dismissMessage() }
                    } message: {
                        Text(snapshot.message ?? "")
                    }
                }
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
