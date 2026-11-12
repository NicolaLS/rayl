import SwiftUI
import Shared

private func makeBlinkShellAdapter(_ experience: BlinkIosExperience) -> NativeShellAdapter {
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
        AnyView(
            NativePaymentScanView(
                controller: experience.scanController(),
                recentController: experience.recentController()
            )
        )
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
                            BlinkWalletSettingsActionsView(
                                controller: experience.blinkSettingsController()
                            )
                        )
                    )
                )
            )
        )
    },
    nativeOnboardingView: {
        AnyView(
            BlinkNativeOnboardingView(controller: experience.onboardingController())
        )
    }
)

}

@MainActor
private final class BlinkRemovalRecoveryModel: ObservableObject {
    @Published private(set) var snapshot: BlinkRemovalRecoverySnapshot?
    private var cancel: (() -> Void)?

    init(experience: BlinkIosExperience) {
        cancel = experience.observeRemovalRecovery { [weak self] snapshot in
            self?.snapshot = snapshot
        }
    }

    deinit {
        cancel?()
    }
}

struct BlinkExperienceView: View {
    let experience: BlinkIosExperience
    private let adapter: NativeShellAdapter
    @StateObject private var recovery: BlinkRemovalRecoveryModel

    init(experience: BlinkIosExperience) {
        self.experience = experience
        self.adapter = makeBlinkShellAdapter(experience)
        _recovery = StateObject(wrappedValue: BlinkRemovalRecoveryModel(experience: experience))
    }

    var body: some View {
        Group {
            if let snapshot = recovery.snapshot {
                if snapshot.required {
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
}

@MainActor
private final class BlinkWalletSettingsModel: ObservableObject {
    @Published private(set) var snapshot: BlinkNativeWalletSettingsSnapshot?

    let controller: BlinkNativeSettingsController
    private var cancel: (() -> Void)?

    init(controller: BlinkNativeSettingsController) {
        self.controller = controller
        cancel = controller.observe { [weak self] snapshot in
            self?.snapshot = snapshot
        }
    }

    deinit {
        cancel?()
    }
}

private struct BlinkWalletSettingsActionsView: View {
    @StateObject private var model: BlinkWalletSettingsModel
    @State private var showsFundingWallets = false

    init(controller: BlinkNativeSettingsController) {
        _model = StateObject(wrappedValue: BlinkWalletSettingsModel(controller: controller))
    }

    var body: some View {
        Group {
            if let snapshot = model.snapshot {
                VStack(alignment: .leading, spacing: 12) {
                    Button {
                        showsFundingWallets = true
                        model.controller.loadFundingWallets()
                    } label: {
                        HStack {
                            Label {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(snapshot.fundingWalletTitle)
                                    Text(snapshot.selectedFundingWalletTitle)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            } icon: {
                                Image(systemName: "wallet.bifold")
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.tertiary)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                .sheet(isPresented: $showsFundingWallets) {
                    fundingWalletPicker(snapshot)
                }
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity)
            }
        }
    }

    private func fundingWalletPicker(
        _ snapshot: BlinkNativeWalletSettingsSnapshot
    ) -> some View {
        NavigationStack {
            List {
                if snapshot.isLoadingFundingWallets {
                    HStack(spacing: 12) {
                        ProgressView()
                        Text(snapshot.fundingWalletLoadingTitle)
                    }
                }

                if let message = snapshot.fundingWalletUnavailableMessage {
                    Text(message)
                        .foregroundStyle(.red)
                }

                if let message = snapshot.fundingWalletErrorMessage {
                    Text(message)
                        .foregroundStyle(.red)
                }

                ForEach(snapshot.fundingWalletOptions, id: \.id) { option in
                    Button {
                        model.controller.selectFundingWallet(id: option.id)
                        showsFundingWallets = false
                    } label: {
                        HStack {
                            Text(option.title)
                                .foregroundStyle(.primary)
                            Spacer()
                            if option.selected {
                                Image(systemName: "checkmark")
                                    .fontWeight(.semibold)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .navigationTitle(snapshot.fundingWalletPickerTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(snapshot.fundingWalletCloseTitle) {
                        showsFundingWallets = false
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }
}
