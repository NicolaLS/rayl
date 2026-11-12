import SwiftUI
import Shared

private func makeNwcShellAdapter(_ experience: NwcIosExperience) -> NativeShellAdapter {
    NativeShellAdapter(
    initialStage: {
        experience.isOnboarded() ? .tabs : .onboarding
    },
    initialSelectedTab: experience.selectedTab,
    selectTab: { experience.selectTab(tab: $0) },
    observeStage: { observer in
        experience.observeOnboarded { value in
            observer.send(value.boolValue ? .tabs : .onboarding)
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
                // Back from adding a card returns to Wallets with Add a card open.
                openedOnAppear: experience.cardsController().reopensAddCard()
                    ? AnyView(NativeWalletCardsAddAgain(controller: experience.cardsController()))
                    : nil,
                leadingContent: AnyView(
                    NativeWalletCardsEntry(
                        controller: experience.cardsController(),
                        payingContent: AnyView(
                            NwcWalletSettingsActions(
                                controller: experience.walletSettingsController(),
                                onboardingController: experience.onboardingController()
                            )
                        )
                    )
                )
            )
        )
    },
    nativeOnboardingView: {
        AnyView(
            NwcNativeOnboardingView(controller: experience.onboardingController())
        )
    }
)

}

@MainActor
private final class NwcRemovalModel: ObservableObject {
    @Published private(set) var snapshot: NwcRemovalSnapshot?
    private var cancel: (() -> Void)?

    init(experience: NwcIosExperience) {
        cancel = experience.observeRemoval { [weak self] snapshot in
            self?.snapshot = snapshot
        }
    }

    deinit { cancel?() }
}

struct NwcExperienceView: View {
    let experience: NwcIosExperience
    private let adapter: NativeShellAdapter
    @StateObject private var removal: NwcRemovalModel

    init(experience: NwcIosExperience) {
        self.experience = experience
        self.adapter = makeNwcShellAdapter(experience)
        _removal = StateObject(wrappedValue: NwcRemovalModel(experience: experience))
    }

    var body: some View {
        if let snapshot = removal.snapshot {
            if snapshot.pending {
                NativeWalletRemovalRecoveryView(
                    title: snapshot.title,
                    message: snapshot.message,
                    retryTitle: snapshot.retryTitle,
                    isWorking: snapshot.isWorking,
                    onRetry: experience.retryRemoval
                )
            } else {
                NativeAppShell(adapter: adapter)
            }
        } else {
            ProgressView()
        }
    }
}

@MainActor
private final class NwcNativeWalletSettingsModel: ObservableObject {
    @Published private(set) var snapshot: NwcNativeWalletSettingsSnapshot?

    let controller: NwcNativeWalletSettingsController
    private var cancel: (() -> Void)?

    init(controller: NwcNativeWalletSettingsController) {
        self.controller = controller
        cancel = controller.observe { [weak self] snapshot in
            self?.snapshot = snapshot
        }
    }

    deinit {
        cancel?()
    }
}

private struct NwcWalletSettingsActions: View {
    @StateObject private var model: NwcNativeWalletSettingsModel
    let onboardingController: NwcNativeOnboardingController

    init(
        controller: NwcNativeWalletSettingsController,
        onboardingController: NwcNativeOnboardingController
    ) {
        _model = StateObject(wrappedValue: NwcNativeWalletSettingsModel(controller: controller))
        self.onboardingController = onboardingController
    }

    var body: some View {
        Group {
            if let snapshot = model.snapshot {
                // The connection's details, right on the card's page.
                VStack(alignment: .leading, spacing: 12) {
                    if let title = snapshot.walletTitle {
                        Text(title)
                            .font(.headline)
                            .textSelection(.enabled)
                    }
                    NwcWalletDetail(label: snapshot.walletTypeLabel, value: snapshot.walletType)
                    if let connectionId = snapshot.walletId {
                        NwcWalletDetail(label: snapshot.connectionIdLabel, value: connectionId)
                    }
                    if let message = snapshot.removalErrorMessage {
                        Text(message).foregroundStyle(.red)
                    }
                }
                .accessibilityIdentifier("nwc_wallet_details")
            } else {
                ProgressView()
            }
        }
        .sheet(isPresented: walletFlowPresented) {
            NwcNativeOnboardingView(controller: onboardingController)
        }
    }

    private var walletFlowPresented: Binding<Bool> {
        Binding(
            get: { model.snapshot?.walletFlowPresented == true },
            set: { presented in
                if !presented {
                    model.controller.finishWalletConnection()
                }
            }
        )
    }
}

private struct NwcWalletDetail: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(value)
                .textSelection(.enabled)
        }
    }
}
