import SwiftUI
import UIKit

/// One thing the app does for the user, shown on the welcome.
struct NativeOnboardingBenefit: Identifiable {
    let id: Int
    let symbol: String
    let title: String
    let body: String
}

/// The first screen: who Rayl is and, in a few lines, what to expect from it.
struct NativeOnboardingWelcomeView: View {
    let title: String
    let description: String
    let benefits: [NativeOnboardingBenefit]
    let actionTitle: String
    let action: () -> Void

    var body: some View {
        NativeOnboardingLayout {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    RaylMarkView()
                        .frame(width: 88, height: 88)
                        .padding(.top, 28)
                        .padding(.bottom, 12)
                        .accessibilityHidden(true)
                    Text(title)
                        .font(.largeTitle.bold())
                    Text(description)
                        .foregroundStyle(.secondary)
                        .padding(.bottom, 20)
                    ForEach(benefits) { benefit in
                        HStack(alignment: .top, spacing: 16) {
                            Image(systemName: benefit.symbol)
                                .font(.system(size: 18, weight: .medium))
                                .foregroundStyle(.tint)
                                .frame(width: 40, height: 40)
                                .background(Color.accentColor.opacity(0.12), in: Circle())
                                .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(benefit.title)
                                    .font(.headline)
                                Text(benefit.body)
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .padding(.vertical, 8)
                        .accessibilityElement(children: .combine)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            NativeOnboardingPrimaryButton(title: actionTitle, action: action)
        }
    }
}

/// Rayl's mark as on the app icon: four rails of light meeting at one point over deep navy. It
/// keeps the icon's colours in both appearances.
struct RaylMarkView: View {
    // Where each rail leaves the tile, as fractions of its size.
    private static let rails: [[CGPoint]] = [
        [CGPoint(x: 0, y: 0.585), CGPoint(x: 0, y: 0.76)],
        [CGPoint(x: 0, y: 0.87), CGPoint(x: 0, y: 1), CGPoint(x: 0.075, y: 1)],
        [CGPoint(x: 0.245, y: 1), CGPoint(x: 0.44, y: 1)],
        [CGPoint(x: 0.515, y: 1), CGPoint(x: 0.66, y: 1)],
    ]
    private static let origin = UnitPoint(x: 0.672, y: 0.33)
    private static let near = Color(red: 0.84, green: 0.90, blue: 1)
    private static let far = Color(red: 0.23, green: 0.49, blue: 0.86)

    var body: some View {
        GeometryReader { geometry in
            let size = geometry.size
            let origin = CGPoint(x: size.width * Self.origin.x, y: size.height * Self.origin.y)
            ZStack {
                RadialGradient(
                    colors: [
                        Color(red: 0.07, green: 0.16, blue: 0.29),
                        Color(red: 0.04, green: 0.08, blue: 0.13),
                    ],
                    center: Self.origin,
                    startRadius: 0,
                    endRadius: size.width * 0.9
                )
                Path { path in
                    for rail in Self.rails {
                        path.move(to: origin)
                        for point in rail {
                            path.addLine(to: CGPoint(x: size.width * point.x, y: size.height * point.y))
                        }
                        path.closeSubpath()
                    }
                }
                .fill(LinearGradient(colors: [Self.near, Self.far], startPoint: Self.origin, endPoint: .bottomLeading))
                RadialGradient(
                    colors: [Self.near.opacity(0.55), .clear],
                    center: Self.origin,
                    startRadius: 0,
                    endRadius: size.width * 0.16
                )
            }
            .clipShape(RoundedRectangle(cornerRadius: size.width * 0.23, style: .continuous))
        }
    }
}

struct NativeOnboardingAutoPayView: View {
    let title: String
    let description: String
    let alwaysTitle: String
    let thresholdTitle: String
    let thresholdLabel: String
    let hint: String
    let actionTitle: String
    let confirmationMode: String
    let thresholdIndex: Int32
    let thresholdStepCount: Int32
    let onConfirmationModeChanged: (String) -> Void
    let onThresholdIndexChanged: (Int32) -> Void
    let action: () -> Void

    init(
        title: String,
        body: String,
        alwaysTitle: String,
        thresholdTitle: String,
        thresholdLabel: String,
        hint: String,
        actionTitle: String,
        confirmationMode: String,
        thresholdIndex: Int32,
        thresholdStepCount: Int32,
        onConfirmationModeChanged: @escaping (String) -> Void,
        onThresholdIndexChanged: @escaping (Int32) -> Void,
        action: @escaping () -> Void
    ) {
        self.title = title
        description = body
        self.alwaysTitle = alwaysTitle
        self.thresholdTitle = thresholdTitle
        self.thresholdLabel = thresholdLabel
        self.hint = hint
        self.actionTitle = actionTitle
        self.confirmationMode = confirmationMode
        self.thresholdIndex = thresholdIndex
        self.thresholdStepCount = thresholdStepCount
        self.onConfirmationModeChanged = onConfirmationModeChanged
        self.onThresholdIndexChanged = onThresholdIndexChanged
        self.action = action
    }

    var body: some View {
        NativeOnboardingLayout {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text(title)
                        .font(.largeTitle.bold())
                    Text(description)
                        .foregroundStyle(.secondary)

                    VStack(alignment: .leading, spacing: 16) {
                        confirmationOption(alwaysTitle, mode: "always")
                        Divider()
                        confirmationOption(thresholdTitle, mode: "above")

                        if confirmationMode == "above" {
                            Divider()
                            Text(thresholdLabel)
                                .font(.headline)
                                .foregroundStyle(.tint)
                            Slider(
                                value: Binding(
                                    get: { Double(thresholdIndex) },
                                    set: { onThresholdIndexChanged(Int32($0.rounded())) }
                                ),
                                in: 0...Double(max(0, thresholdStepCount - 1)),
                                step: 1
                            )
                            .accessibilityLabel(thresholdTitle)
                            .accessibilityValue(thresholdLabel)
                        }
                    }
                    .padding()
                    .background(
                        Color(uiColor: .secondarySystemGroupedBackground),
                        in: RoundedRectangle(cornerRadius: 20))

                    Text(hint)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            NativeOnboardingPrimaryButton(title: actionTitle, action: action)
        }
    }

    private func confirmationOption(_ title: String, mode: String) -> some View {
        Button {
            onConfirmationModeChanged(mode)
        } label: {
            HStack(spacing: 16) {
                Text(title)
                    .font(.body.weight(.medium))
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                Image(systemName: confirmationMode == mode ? "checkmark.circle.fill" : "circle")
                    .font(.title2)
                    .foregroundStyle(confirmationMode == mode ? Color.accentColor : Color.secondary)
                    .accessibilityHidden(true)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(confirmationMode == mode ? .isSelected : [])
    }
}

struct NativeOnboardingAgreementView: View {
    let title: String
    let description: String
    let checkboxTitle: String
    let actionTitle: String
    let hasAgreed: Bool
    let isWorking: Bool
    let message: String?
    let secondaryActionTitle: String?
    let onAgreementChanged: (Bool) -> Void
    let action: () -> Void
    let secondaryAction: () -> Void

    /// `secondaryActionTitle` adds a second way to continue under the primary one.
    init(
        title: String,
        body: String,
        checkboxTitle: String,
        actionTitle: String,
        hasAgreed: Bool,
        isWorking: Bool = false,
        message: String? = nil,
        secondaryActionTitle: String? = nil,
        onAgreementChanged: @escaping (Bool) -> Void,
        action: @escaping () -> Void,
        secondaryAction: @escaping () -> Void = {}
    ) {
        self.title = title
        description = body
        self.checkboxTitle = checkboxTitle
        self.actionTitle = actionTitle
        self.hasAgreed = hasAgreed
        self.isWorking = isWorking
        self.message = message
        self.secondaryActionTitle = secondaryActionTitle
        self.onAgreementChanged = onAgreementChanged
        self.action = action
        self.secondaryAction = secondaryAction
    }

    var body: some View {
        NativeOnboardingLayout {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text(title)
                        .font(.largeTitle.bold())
                    Text(description)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            if let message {
                Text(message)
                    .font(.callout)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            // Consent sits with the action it unlocks.
            Toggle(
                checkboxTitle,
                isOn: Binding(
                    get: { hasAgreed },
                    set: onAgreementChanged
                )
            )
            .font(.subheadline)
            .tint(.accentColor)
            .disabled(isWorking)
            .padding()
            .background(
                Color(uiColor: .secondarySystemGroupedBackground),
                in: RoundedRectangle(cornerRadius: 16))

            if isWorking {
                ProgressView()
                    .frame(maxWidth: .infinity, minHeight: 44)
            } else {
                NativeOnboardingPrimaryButton(title: actionTitle, action: action)
                    .disabled(!hasAgreed)
                if let secondaryActionTitle {
                    Button(secondaryActionTitle, action: secondaryAction)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .disabled(!hasAgreed)
                }
            }
        }
    }
}

/// The frame of a full-screen onboarding step.
struct NativeOnboardingLayout<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(spacing: 20) {
            content()
        }
        .padding(.horizontal, 24)
        .padding(.top, 12)
        .padding(.bottom, 20)
        .frame(maxWidth: 608)
        .frame(maxWidth: .infinity)
        .background(Color(uiColor: .systemGroupedBackground))
    }
}

struct NativeOnboardingPrimaryButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.headline)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity, minHeight: 28)
                .padding(.vertical, 4)
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.roundedRectangle(radius: 16))
        .controlSize(.large)
    }
}

/// Provider-neutral connection layout. Credential validation and connection work stay in Kotlin.
struct NativeCredentialConnectionView<Content: View>: View {
    let title: String
    let description: String
    let actionTitle: String
    let canSubmit: Bool
    let isSubmitting: Bool
    /// Offered, before the form, only where getting the credential needs a guide.
    var instructionsTitle: String?
    /// What connecting does beyond storing the credential, when the user must hear about it.
    var connectionNotice: String?
    var privacyTitle: String = ""
    var privacyPolicyUrl: String?
    var termsTitle: String = ""
    var termsUrl: String?
    var onInstructions: (() -> Void)?
    let onSubmit: () -> Void
    @ViewBuilder var content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(description)
                    .foregroundStyle(.secondary)
                if let instructionsTitle, let onInstructions {
                    Button(action: onInstructions) {
                        Label(instructionsTitle, systemImage: "questionmark.circle")
                    }
                    .disabled(isSubmitting)
                }
                content()
                if let connectionNotice {
                    Text(connectionNotice)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 8)
            .padding(.bottom, 16)
            .frame(maxWidth: 608, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .background(Color(uiColor: .systemGroupedBackground))
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 12) {
                Button {
                    UIApplication.shared.sendAction(
                        #selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil
                    )
                    if canSubmit { onSubmit() }
                } label: {
                    HStack {
                        if isSubmitting { ProgressView() }
                        Text(actionTitle)
                    }
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 28)
                    .padding(.vertical, 4)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.roundedRectangle(radius: 16))
                .controlSize(.large)
                .disabled(!canSubmit)
                HStack(spacing: 20) {
                    if let value = privacyPolicyUrl, let url = URL(string: value) {
                        Link(privacyTitle, destination: url)
                    }
                    if let value = termsUrl, let url = URL(string: value) {
                        Link(termsTitle, destination: url)
                    }
                }
                .font(.footnote)
                .tint(.secondary)
            }
            .padding(.horizontal, 24)
            .padding(.top, 12)
            .padding(.bottom, 8)
            .frame(maxWidth: 608)
            .frame(maxWidth: .infinity)
            .background(Color(uiColor: .systemGroupedBackground))
        }
    }
}

/// Fills a credential from the clipboard; pasting is how most credentials arrive.
struct NativeCredentialPasteButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: "doc.on.clipboard")
        }
        .buttonStyle(.bordered)
        .buttonBorderShape(.capsule)
    }
}

struct NativeCredentialInput: View {
    @Binding var value: String
    let label: String
    let placeholder: String
    let showTitle: String
    let hideTitle: String
    let pasteTitle: String
    var enabled = true
    var multiline = false
    let onPaste: () -> Void
    let onSubmit: () -> Void
    @State private var visible = false
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: multiline ? .top : .center) {
                Group {
                    if visible {
                        TextField(placeholder, text: $value, axis: multiline ? .vertical : .horizontal)
                            .lineLimit(multiline ? 4...8 : 1...1)
                    } else {
                        SecureField(placeholder, text: $value)
                    }
                }
                .textContentType(.password)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.asciiCapable)
                .focused($focused)
                .submitLabel(.go)
                .onSubmit {
                    focused = false
                    onSubmit()
                }
                .privacySensitive()
                .accessibilityLabel(label)
                .disabled(!enabled)
                .frame(minHeight: multiline ? 112 : 44, alignment: multiline ? .topLeading : .leading)

                Button { visible.toggle() } label: {
                    Image(systemName: visible ? "eye.slash" : "eye")
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel(visible ? hideTitle : showTitle)
                .disabled(!enabled)
            }
            .padding()
            .background(
                Color(uiColor: .secondarySystemGroupedBackground),
                in: RoundedRectangle(cornerRadius: 16)
            )
            NativeCredentialPasteButton(title: pasteTitle, action: onPaste)
                .disabled(!enabled)
        }
        .onDisappear {
            visible = false
            focused = false
        }
    }
}
