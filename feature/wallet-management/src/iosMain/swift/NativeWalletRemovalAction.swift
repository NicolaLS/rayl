import SwiftUI

/// The common top-level action that disconnects an external wallet and returns to Rayl's own wallet.
/// It needs no confirmation: only the connection is removed, and the wallet can be connected again.
struct NativeWalletRemovalAction: View {
    let title: String
    /// Shown while a payment is submitted and the action is unavailable.
    let busyMessage: String
    let canRemove: Bool
    let onRemove: () -> Void

    var body: some View {
        Button(action: onRemove) {
            VStack(alignment: .leading, spacing: 4) {
                Label(title, systemImage: "arrow.uturn.backward")
                if !canRemove {
                    Text(busyMessage)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!canRemove)
        .accessibilityIdentifier("settings_remove_wallet")
    }
}
