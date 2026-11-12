import Shared
import SwiftUI
import UIKit

@MainActor
final class NativeWalletCardsModel: ObservableObject {
    @Published private(set) var snapshot: NativeWalletCardsSnapshot?

    let controller: NativeWalletCardsController
    private var cancel: (() -> Void)?

    init(controller: NativeWalletCardsController) {
        self.controller = controller
        cancel = controller.observe { [weak self] in self?.snapshot = $0 }
    }

    // The wallet's experience owns the controller and clears it when it unmounts.
    deinit { cancel?() }
}

/// The wallet block at the top of every wallet's Settings, the same for every wallet: the active
/// wallet, which opens the list to pick another or add one; its balance with Add funds; and
/// Details, its page. Each part is its own view, so each becomes its own list row.
struct NativeWalletCardsEntry: View {
    @StateObject private var model: NativeWalletCardsModel
    private let balance: AnyView?
    private let payingContent: AnyView?

    /// `balance` is the active wallet's balance row; without one the block shows the shared
    /// placeholder. `payingContent` holds the settings only the active wallet has, on its page.
    init(
        controller: NativeWalletCardsController,
        balance: AnyView? = nil,
        payingContent: AnyView? = nil
    ) {
        _model = StateObject(wrappedValue: NativeWalletCardsModel(controller: controller))
        self.balance = balance
        self.payingContent = payingContent
    }

    var body: some View {
        if let snapshot = model.snapshot {
            NavigationLink {
                NativeWalletCardsList(model: model)
            } label: {
                HStack(spacing: 12) {
                    NativeWalletCardMark(
                        provider: snapshot.paying.provider, name: snapshot.paying.name, size: 40)
                    Text(snapshot.paying.name)
                        .font(.headline)
                }
            }
            .accessibilityIdentifier("settings_wallets")
            if let balance {
                balance
            } else {
                // Cards are funded in their own apps: their balance is only shown.
                NativeWalletBalanceLabel(
                    title: snapshot.balanceTitle,
                    balance: snapshot.cardBalance,
                    action: snapshot.addFundsTitle,
                    enabled: false
                )
                .onAppear { model.controller.refreshBalance() }
            }
            NavigationLink {
                NativeWalletCardPage(model: model, payingContent: payingContent)
            } label: {
                Label(snapshot.detailsTitle, systemImage: "info.circle")
            }
            .accessibilityIdentifier("settings_wallet_details")
        } else {
            ProgressView()
        }
    }
}

/// The balance row of the wallet block, the same for every wallet. Without a balance it is a
/// placeholder: the wallet has no balance or funding to show here yet.
struct NativeWalletBalanceLabel: View {
    let title: String
    let balance: String?
    let action: String?
    let enabled: Bool

    var body: some View {
        HStack {
            // The amount sits under the title, not under the icon.
            Label {
                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                    Text(balance ?? "—")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: "bitcoinsign.circle")
            }
            Spacer()
            if let action {
                Text(action)
                    .foregroundStyle(enabled ? AnyShapeStyle(.tint) : AnyShapeStyle(.tertiary))
            }
        }
        .contentShape(Rectangle())
        .accessibilityIdentifier("settings_balance")
    }
}

/// Wallets with Add a card open, where the user returns after leaving a card's setup. Use it
/// with `NativeSettingsView`'s `openedOnAppear` when `reopensAddCard()` says so.
struct NativeWalletCardsAddAgain: View {
    @StateObject private var model: NativeWalletCardsModel

    init(controller: NativeWalletCardsController) {
        _model = StateObject(wrappedValue: NativeWalletCardsModel(controller: controller))
    }

    var body: some View {
        NativeWalletCardsList(model: model, opensAdd: true)
            .onAppear { model.controller.reopenedAddCard() }
    }
}

/// Picks the wallet that pays: tapping another one activates it, the active one stays.
private struct NativeWalletCardsList: View {
    @ObservedObject var model: NativeWalletCardsModel
    @State private var showsAdd: Bool

    init(model: NativeWalletCardsModel, opensAdd: Bool = false) {
        self.model = model
        _showsAdd = State(initialValue: opensAdd)
    }

    var body: some View {
        if let snapshot = model.snapshot {
            List {
                Section {
                    // Rayl is simply the card every user has from the start.
                    ForEach(snapshot.wallets, id: \.id) { card in
                        Button {
                            if !card.isPaying { model.controller.payWith(id: card.id) }
                        } label: {
                            HStack(spacing: 12) {
                                NativeWalletCardMark(
                                    provider: card.provider, name: card.name, size: 40)
                                Text(card.name)
                                    .foregroundStyle(.primary)
                                Spacer()
                                if card.isPaying {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundStyle(.tint)
                                        .accessibilityLabel(snapshot.activeTitle)
                                }
                            }
                            .contentShape(Rectangle())
                        }
                        .disabled(snapshot.isBusy && !card.isPaying)
                        .accessibilityIdentifier("wallet_card_\(card.id)")
                    }
                    if !snapshot.addable.isEmpty {
                        NavigationLink {
                            NativeWalletCardProviders(model: model)
                        } label: {
                            Label(snapshot.addTitle, systemImage: "plus")
                        }
                        .accessibilityIdentifier("wallet_cards_add")
                    }
                } footer: {
                    if snapshot.isBusy {
                        Text(snapshot.busyMessage)
                    }
                }
            }
            .navigationTitle(snapshot.walletsTitle)
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(isPresented: $showsAdd) {
                NativeWalletCardProviders(model: model)
            }
        }
    }
}

/// The active wallet's page: its mark and name with an edit button, the settings only it has,
/// and Remove card at the bottom. Rayl is never renamed or removed.
private struct NativeWalletCardPage: View {
    @ObservedObject var model: NativeWalletCardsModel
    let payingContent: AnyView?
    @State private var renaming = false
    @State private var newName = ""
    // Read only to know whether there is one; shown on its own screen.
    @State private var recoveryPhrase: String?

    var body: some View {
        if let snapshot = model.snapshot {
            let card = snapshot.paying
            List {
                Section {
                    header(card, snapshot)
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets(top: 8, leading: 4, bottom: 8, trailing: 4))
                }
                if let payingContent {
                    Section {
                        payingContent
                    }
                }
                if let recoveryPhrase {
                    Section {
                        NavigationLink {
                            NativeRecoveryPhraseView(
                                phrase: recoveryPhrase,
                                title: snapshot.recoveryPhraseTitle,
                                message: snapshot.recoveryPhraseBody,
                                copyTitle: snapshot.copyTitle
                            )
                        } label: {
                            Label(snapshot.recoveryPhraseTitle, systemImage: "key")
                        }
                        .accessibilityIdentifier("wallet_card_recovery_phrase")
                    }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .bottom) {
                if !card.isPocket {
                    remove(snapshot)
                }
            }
            .task(id: card.id) {
                model.controller.recoveryPhrase(id: card.id) { recoveryPhrase = $0 }
            }
            .alert(snapshot.renameTitle, isPresented: $renaming) {
                TextField(snapshot.renameTitle, text: $newName)
                Button(snapshot.cancelTitle, role: .cancel) {}
                Button(snapshot.saveTitle) {
                    model.controller.rename(id: card.id, name: newName)
                }
            }
        }
    }

    /// The mark and the name in large type with its edit button.
    private func header(_ card: NativeWalletCardRow, _ snapshot: NativeWalletCardsSnapshot)
        -> some View
    {
        HStack(spacing: 16) {
            NativeWalletCardMark(provider: card.provider, name: card.name, size: 64)
            Text(card.name)
                .font(.title.bold())
                .lineLimit(2)
            // Rayl keeps its name.
            if !card.isPocket {
                Button {
                    newName = card.name
                    renaming = true
                } label: {
                    Image(systemName: "pencil")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(snapshot.renameTitle)
                .accessibilityIdentifier("wallet_card_rename")
            }
        }
    }

    /// Removing needs no confirmation: the funds stay in the wallet itself.
    private func remove(_ snapshot: NativeWalletCardsSnapshot) -> some View {
        VStack(spacing: 8) {
            if snapshot.isBusy {
                Text(snapshot.busyMessage)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Button(role: .destructive) {
                model.controller.removePaying()
            } label: {
                Text(snapshot.removeTitle)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .disabled(snapshot.isBusy)
            .accessibilityIdentifier("wallet_card_remove")
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .background(.bar)
    }
}

private struct NativeWalletCardProviders: View {
    @ObservedObject var model: NativeWalletCardsModel

    var body: some View {
        if let snapshot = model.snapshot {
            List {
                Section {
                    ForEach(snapshot.addable, id: \.id) { provider in
                        Button {
                            model.controller.add(provider: provider.id)
                        } label: {
                            HStack(spacing: 14) {
                                NativeWalletCardMark(
                                    provider: provider.id, name: provider.title, size: 44)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(provider.title)
                                        .foregroundStyle(.primary)
                                    Text(provider.subtitle)
                                        .font(.footnote)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                        .disabled(snapshot.isBusy)
                        .accessibilityIdentifier("wallet_cards_add_\(provider.id)")
                    }
                } header: {
                    Text(snapshot.addBody)
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .textCase(nil)
                        .padding(.bottom, 8)
                } footer: {
                    if snapshot.isBusy {
                        Text(snapshot.busyMessage)
                    }
                }
            }
            .navigationTitle(snapshot.addTitle)
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

/// Rayl's own mark for the pocket, the app's `wallet_<provider>` image, or the name's initial.
private struct NativeWalletCardMark: View {
    let provider: String
    let name: String
    let size: CGFloat

    var body: some View {
        Group {
            if provider == "rayl" {
                RaylMarkView()
            } else if let icon = UIImage(named: "wallet_\(provider)") {
                Image(uiImage: icon)
                    .resizable()
                    .scaledToFit()
            } else {
                Circle()
                    .fill(Color.accentColor.opacity(0.2))
                    .overlay(Text(String(name.prefix(1))).font(.headline))
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// The words of a wallet whose only backup they are.
private struct NativeRecoveryPhraseView: View {
    let phrase: String
    let title: String
    let message: String
    let copyTitle: String

    var body: some View {
        List {
            Section {
                Text(phrase)
                    .font(.body.monospaced())
                    .privacySensitive()
                    .accessibilityIdentifier("rayl_recovery_phrase_words")
                Button(copyTitle) {
                    // Local only, so the words never reach other devices through Handoff.
                    UIPasteboard.general.setItems(
                        [[UIPasteboard.typeAutomatic: phrase]],
                        options: [.localOnly: true]
                    )
                }
            } footer: {
                Text(message)
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}
