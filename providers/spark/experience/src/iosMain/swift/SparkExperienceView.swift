import SwiftUI
import Shared

private func makeSparkShellAdapter(_ experience: SparkIosExperience) -> NativeShellAdapter {
    NativeShellAdapter(
        initialStage: {
            switch experience.stage() {
            case "tabs": return .tabs
            case "onboarding": return .onboarding
            default: return .loading
            }
        },
        initialSelectedTab: experience.selectedTab,
        selectTab: { experience.selectTab(tab: $0) },
        observeStage: { observer in
            experience.observeStage { value in
                switch value {
                case "tabs": observer.send(.tabs)
                case "onboarding": observer.send(.onboarding)
                default: observer.send(.loading)
                }
            }
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
                        SparkWalletSettingsActions(
                            controller: experience.walletSettingsController()
                        )
                    )
                )
            )
        },
        nativeOnboardingView: {
            AnyView(SparkNativeOnboardingView(controller: experience.onboardingController()))
        }
    )
}

struct SparkExperienceView: View {
    private let adapter: NativeShellAdapter

    init(experience: SparkIosExperience) {
        adapter = makeSparkShellAdapter(experience)
    }

    var body: some View {
        NativeAppShell(adapter: adapter)
    }
}

@MainActor
private final class SparkNativeWalletSettingsModel: ObservableObject {
    @Published private(set) var snapshot: SparkNativeWalletSettingsSnapshot?

    let controller: SparkNativeWalletSettingsController
    private var cancel: (() -> Void)?

    init(controller: SparkNativeWalletSettingsController) {
        self.controller = controller
        cancel = controller.observe { [weak self] snapshot in
            self?.snapshot = snapshot
        }
    }

    deinit {
        cancel?()
    }
}

private struct SparkWalletSettingsActions: View {
    @StateObject private var model: SparkNativeWalletSettingsModel

    init(controller: SparkNativeWalletSettingsController) {
        _model = StateObject(wrappedValue: SparkNativeWalletSettingsModel(controller: controller))
    }

    var body: some View {
        Group {
            if let snapshot = model.snapshot {
                VStack(alignment: .leading, spacing: 12) {
                    NativeWalletRemovalAction(
                        title: snapshot.removeTitle,
                        busyMessage: snapshot.removeBusyMessage,
                        canRemove: !snapshot.isWorking,
                        onRemove: model.controller.removeWallet
                    )
                    if let message = snapshot.errorMessage {
                        Text(message).foregroundStyle(.red)
                    }
                }
            } else {
                ProgressView()
            }
        }
    }
}
