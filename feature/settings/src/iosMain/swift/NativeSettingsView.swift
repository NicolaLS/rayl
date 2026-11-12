import SwiftUI
import Shared
import UIKit

@MainActor
final class NativeSettingsViewModel: ObservableObject {
    @Published private(set) var snapshot: NativeSettingsSnapshot?

    let controller: NativeSettingsController
    private var cancel: (() -> Void)?

    init(controller: NativeSettingsController) {
        self.controller = controller
        cancel = controller.observe { [weak self] snapshot in
            self?.snapshot = snapshot
        }
    }

    deinit {
        cancel?()
    }
}

struct NativeSettingsView: View {
    @StateObject private var model: NativeSettingsViewModel
    private let leadingContent: AnyView?
    private let trailingContent: AnyView?
    private let openedOnAppear: AnyView?
    @State private var confirmUsageDataOff = false
    @State private var opens = false
    @State private var opened = false

    /// `openedOnAppear` is pushed once when Settings first appears, e.g. to return the user to
    /// the screen they left.
    init(
        controller: NativeSettingsController,
        openedOnAppear: AnyView? = nil,
        leadingContent: AnyView? = nil,
        trailingContent: AnyView? = nil
    ) {
        _model = StateObject(wrappedValue: NativeSettingsViewModel(controller: controller))
        self.leadingContent = leadingContent
        self.trailingContent = trailingContent
        self.openedOnAppear = openedOnAppear
    }

    var body: some View {
        // The stack stays outside the snapshot check. Inserting a `NavigationStack` into a tab
        // that is already on screen makes UIKit lay out one navigation bar's item in another's.
        NavigationStack {
            Group {
                if let snapshot = model.snapshot {
                    settings(snapshot)
                } else {
                    ProgressView()
                }
            }
        }
    }

    private func settings(_ snapshot: NativeSettingsSnapshot) -> some View {
        Group {
            List {
                if let leadingContent {
                    Section {
                        leadingContent
                    }
                }

                Section(snapshot.text.paymentsTitle) {
                    NativeConfirmationRow(snapshot: snapshot, controller: model.controller)

                    NavigationLink {
                        NativeOptionPicker(
                            title: snapshot.text.currencyTitle,
                            searchPrompt: snapshot.text.currencySearch,
                            options: snapshot.currencyOptions,
                            selectedId: snapshot.selectedCurrencyId,
                            onSelect: model.controller.selectCurrency
                        )
                    } label: {
                        settingsLabel(
                            title: snapshot.text.currencyTitle,
                            subtitle: optionTitle(
                                snapshot.currencyOptions,
                                selectedId: snapshot.selectedCurrencyId
                            ),
                            symbol: "bitcoinsign.circle"
                        )
                    }

                    Toggle(
                        isOn: Binding(
                            get: { snapshot.payment.offerToSaveNewTargets },
                            set: model.controller.setOfferToSaveNewTargets
                        )
                    ) {
                        settingsLabel(
                            title: snapshot.text.paymentOfferSaveTargets,
                            subtitle: snapshot.text.paymentOfferSaveTargetsSubtitle,
                            symbol: "person.crop.circle.badge.plus"
                        )
                    }
                }

                Section(snapshot.text.appSectionTitle) {
                    if snapshot.languageManagedBySystem {
                        Button(action: openSystemSettings) {
                            HStack {
                                settingsLabel(
                                    title: snapshot.text.languageTitle,
                                    subtitle: [
                                        optionTitle(
                                            snapshot.languageOptions,
                                            selectedId: snapshot.selectedLanguageId
                                        ),
                                        snapshot.text.languageSystemSettingsHint
                                    ].joined(separator: " · "),
                                    symbol: "globe"
                                )
                                Spacer()
                                Image(systemName: "arrow.up.forward.app")
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .buttonStyle(.plain)
                    } else {
                        choice(
                            title: snapshot.text.languageTitle,
                            symbol: "globe",
                            options: snapshot.languageOptions,
                            selectedId: snapshot.selectedLanguageId,
                            onSelect: model.controller.selectLanguage
                        )
                    }

                    choice(
                        title: snapshot.text.themeTitle,
                        symbol: "circle.lefthalf.filled",
                        options: snapshot.themeOptions,
                        selectedId: snapshot.selectedThemeId,
                        onSelect: model.controller.selectTheme
                    )

                    Toggle(
                        isOn: Binding(
                            get: { snapshot.payment.vibrateOnScan },
                            set: model.controller.setVibrateOnScan
                        )
                    ) {
                        Label {
                            Text(snapshot.text.paymentHapticsScan)
                        } icon: {
                            Image(systemName: "qrcode.viewfinder").foregroundStyle(.tint)
                        }
                    }

                    Toggle(
                        isOn: Binding(
                            get: { snapshot.payment.vibrateOnPayment },
                            set: model.controller.setVibrateOnPayment
                        )
                    ) {
                        Label {
                            Text(snapshot.text.paymentHapticsPayment)
                        } icon: {
                            Image(systemName: "iphone.radiowaves.left.and.right").foregroundStyle(.tint)
                        }
                    }
                }

                Section(snapshot.text.privacySectionTitle) {
                    // Turning usage data off asks once, with the reason to keep it.
                    Toggle(
                        isOn: Binding(
                            get: { snapshot.usageDataEnabled },
                            set: { enabled in
                                if enabled {
                                    model.controller.setUsageDataEnabled(enabled: true)
                                } else {
                                    confirmUsageDataOff = true
                                    model.controller.usageDataAsked()
                                }
                            }
                        )
                    ) {
                        settingsLabel(
                            title: snapshot.text.usageDataTitle,
                            subtitle: snapshot.text.usageDataSubtitle,
                            symbol: "chart.bar"
                        )
                    }
                    .accessibilityIdentifier("settings_usage_data_row")
                }

                if let trailingContent {
                    Section {
                        trailingContent
                    }
                }

                Section {
                    NativeSettingsFooter(snapshot: snapshot)
                }
            }
            .alert(snapshot.text.usageDataOffTitle, isPresented: $confirmUsageDataOff) {
                Button(snapshot.text.usageDataOffKeep, role: .cancel) {
                    model.controller.usageDataKeptOn()
                }
                Button(snapshot.text.usageDataOffConfirm, role: .destructive) {
                    model.controller.setUsageDataEnabled(enabled: false)
                }
            } message: {
                Text(snapshot.text.usageDataOffBody)
            }
            .navigationTitle(snapshot.text.settingsTitle)
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(isPresented: $opens) {
                openedOnAppear
            }
            .onAppear {
                if openedOnAppear != nil && !opened {
                    opened = true
                    opens = true
                }
            }
        }
    }

    private func settingsLabel(
        title: String,
        subtitle: String,
        symbol: String
    ) -> some View {
        Label {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        } icon: {
            Image(systemName: symbol)
                .foregroundStyle(.tint)
        }
    }

    /// A setting with a few fixed answers, picked in place from a menu.
    private func choice(
        title: String,
        symbol: String,
        options: [NativeSettingsOption],
        selectedId: String,
        onSelect: @escaping (String) -> Void
    ) -> some View {
        Picker(
            selection: Binding(get: { selectedId }, set: onSelect)
        ) {
            ForEach(options, id: \.id) { option in
                Text(option.title).tag(option.id)
            }
        } label: {
            Label {
                Text(title)
            } icon: {
                Image(systemName: symbol).foregroundStyle(.tint)
            }
        }
        .pickerStyle(.menu)
    }

    private func optionTitle(
        _ options: [NativeSettingsOption],
        selectedId: String
    ) -> String {
        options.first { $0.id == selectedId }?.title ?? selectedId
    }

    private func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

private struct NativeSettingsFooter: View {
    let snapshot: NativeSettingsSnapshot

    var body: some View {
        VStack(spacing: 8) {
            Text(snapshot.versionText)
                .font(.caption2)
                .foregroundStyle(.secondary)

            HStack(spacing: 16) {
                ForEach(snapshot.legalLinks, id: \.url) { link in
                    if let url = URL(string: link.url) {
                        Link(link.title, destination: url)
                            .font(.caption2)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity)
        .multilineTextAlignment(.center)
        .listRowBackground(Color.clear)
    }
}

private struct NativeOptionPicker: View {
    let title: String
    var searchPrompt: String? = nil
    let options: [NativeSettingsOption]
    let selectedId: String
    let onSelect: (String) -> Void

    @State private var query = ""

    var body: some View {
        Group {
            if let searchPrompt {
                optionsList.searchable(text: $query, prompt: Text(searchPrompt))
            } else {
                optionsList
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }

    private var optionsList: some View {
        List(filteredOptions, id: \.id) { option in
            Button {
                onSelect(option.id)
            } label: {
                HStack {
                    Text(option.title)
                        .foregroundStyle(.primary)
                    Spacer()
                    if option.id == selectedId {
                        Image(systemName: "checkmark")
                            .fontWeight(.semibold)
                    }
                }
            }
        }
    }

    private var filteredOptions: [NativeSettingsOption] {
        guard !query.isEmpty else { return options }
        return options.filter {
            $0.title.localizedCaseInsensitiveContains(query)
        }
    }
}

/// Always, or only from an amount on, chosen right where the rule is named.
private struct NativeConfirmationRow: View {
    let snapshot: NativeSettingsSnapshot
    let controller: NativeSettingsController

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label {
                Text(snapshot.text.paymentConfirmTitle)
            } icon: {
                Image(systemName: "checkmark.shield").foregroundStyle(.tint)
            }
            Picker(
                snapshot.text.paymentConfirmTitle,
                selection: Binding(
                    get: { payment.confirmationMode },
                    set: { controller.selectConfirmationMode(id: $0) }
                )
            ) {
                Text(snapshot.text.paymentAlways).tag("always")
                Text(snapshot.text.paymentAbove).tag("above")
            }
            .pickerStyle(.segmented)
            .labelsHidden()

            // Only the threshold needs spelling out; "Always" already reads off the selector.
            if payment.confirmationMode == "above" {
                Text(thresholdDescription)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Slider(
                    value: Binding(
                        get: { Double(thresholdIndex) },
                        set: { controller.selectThresholdStep(index: Int32($0.rounded())) }
                    ),
                    in: 0...Double(max(thresholdSteps.count - 1, 0)),
                    step: 1
                )
                .disabled(thresholdSteps.count < 2)
                .accessibilityLabel(snapshot.text.paymentConfirmTitle)
                .accessibilityValue(thresholdDescription)
            }
        }
        .padding(.vertical, 4)
    }

    private var payment: NativePaymentSettingsSnapshot {
        snapshot.payment
    }

    private var thresholdSteps: [Int64] {
        payment.thresholdSteps.map(\.int64Value)
    }

    private var thresholdIndex: Int {
        thresholdSteps.firstIndex(of: payment.thresholdSats) ?? 0
    }

    private var thresholdDescription: String {
        let sats = payment.thresholdSats.formatted(.number.grouping(.automatic))
        guard let equivalent = payment.thresholdEquivalent else {
            return "\(snapshot.text.paymentAbove) \(sats) SAT"
        }
        return "\(snapshot.text.paymentAbove) \(sats) SAT (\(format(equivalent)))"
    }

    private func format(_ amount: NativeSettingsAmount) -> String {
        let value = Decimal(amount.minor) / pow10(Int(amount.fractionDigits))
        if amount.currencyCode != "SAT" {
            return value.formatted(
                .currency(code: amount.currencyCode)
                    .precision(.fractionLength(Int(amount.fractionDigits)))
            )
        }
        return "\(value.formatted()) \(amount.currencyCode)"
    }

    private func pow10(_ exponent: Int) -> Decimal {
        guard exponent > 0 else { return 1 }
        return (0..<exponent).reduce(Decimal(1)) { value, _ in value * 10 }
    }
}
