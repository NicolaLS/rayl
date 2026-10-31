import SwiftUI
import UIKit

struct NativeOnboardingPageValue: Identifiable {
    let id: Int
    let title: String
    let subtitle: String
    let body: String
}

struct NativeOnboardingWelcomeView: View {
    let title: String
    let subtitle: String
    let description: String
    let actionTitle: String
    let action: () -> Void

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(spacing: 24) {
                    Spacer(minLength: 24)
                    Image(systemName: "qrcode.viewfinder")
                        .font(.system(size: 72, weight: .light))
                        .foregroundStyle(.tint)
                        .frame(width: 152, height: 152)
                        .background(Color.accentColor.opacity(0.08), in: RoundedRectangle(cornerRadius: 36))
                        .accessibilityHidden(true)
                    Text(title)
                        .font(.largeTitle.bold())
                        .multilineTextAlignment(.center)
                    if !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.headline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    Text(description)
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    Spacer(minLength: 24)
                }
                .padding(24)
                .frame(maxWidth: 560)
                .frame(minHeight: geometry.size.height)
                .frame(maxWidth: .infinity)
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            NativeOnboardingPrimaryButton(title: actionTitle, action: action)
                .padding(24)
                .frame(maxWidth: 608)
                .frame(maxWidth: .infinity)
                .background(Color(uiColor: .systemBackground))
        }
    }
}

struct NativeOnboardingFeaturesView: View {
    let pages: [NativeOnboardingPageValue]
    let currentPage: Int32
    let stepIndex: Int32
    let stepCount: Int32
    let actionTitle: String
    let onPageChanged: (Int32) -> Void
    let action: () -> Void

    var body: some View {
        NativeOnboardingProgressLayout(stepIndex: stepIndex, stepCount: stepCount) {
            TabView(
                selection: Binding(
                    get: { currentPage },
                    set: onPageChanged
                )
            ) {
                ForEach(pages) { page in
                    VStack(spacing: 18) {
                        Spacer()
                        Image(systemName: symbol(for: page.id))
                            .font(.system(size: 58, weight: .light))
                            .foregroundStyle(.tint)
                            .accessibilityHidden(true)
                        Text(page.title)
                            .font(.title2.bold())
                            .multilineTextAlignment(.center)
                        Text(page.subtitle)
                            .font(.headline)
                            .multilineTextAlignment(.center)
                        Text(page.body)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                        Spacer()
                    }
                    .padding(.horizontal, 12)
                    .tag(Int32(page.id))
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .always))
            .indexViewStyle(.page(backgroundDisplayMode: .always))

            NativeOnboardingPrimaryButton(title: actionTitle, action: action)
        }
    }

    private func symbol(for index: Int) -> String {
        switch index {
        case 0: return "viewfinder"
        case 1: return "bolt.fill"
        default: return "wallet.bifold"
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
    let stepIndex: Int32
    let stepCount: Int32
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
        stepIndex: Int32,
        stepCount: Int32,
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
        self.stepIndex = stepIndex
        self.stepCount = stepCount
        self.onConfirmationModeChanged = onConfirmationModeChanged
        self.onThresholdIndexChanged = onThresholdIndexChanged
        self.action = action
    }

    var body: some View {
        NativeOnboardingProgressLayout(stepIndex: stepIndex, stepCount: stepCount) {
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
    let stepIndex: Int32
    let stepCount: Int32
    let isWorking: Bool
    let message: String?
    let onAgreementChanged: (Bool) -> Void
    let action: () -> Void

    init(
        title: String,
        body: String,
        checkboxTitle: String,
        actionTitle: String,
        hasAgreed: Bool,
        stepIndex: Int32,
        stepCount: Int32,
        isWorking: Bool = false,
        message: String? = nil,
        onAgreementChanged: @escaping (Bool) -> Void,
        action: @escaping () -> Void
    ) {
        self.title = title
        description = body
        self.checkboxTitle = checkboxTitle
        self.actionTitle = actionTitle
        self.hasAgreed = hasAgreed
        self.stepIndex = stepIndex
        self.stepCount = stepCount
        self.isWorking = isWorking
        self.message = message
        self.onAgreementChanged = onAgreementChanged
        self.action = action
    }

    var body: some View {
        NativeOnboardingProgressLayout(stepIndex: stepIndex, stepCount: stepCount) {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text(title)
                        .font(.largeTitle.bold())
                    Text(description)
                        .foregroundStyle(.secondary)
                    Toggle(
                        checkboxTitle,
                        isOn: Binding(
                            get: { hasAgreed },
                            set: onAgreementChanged
                        )
                    )
                    .tint(.accentColor)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding()
                    .background(
                        Color(uiColor: .secondarySystemGroupedBackground),
                        in: RoundedRectangle(cornerRadius: 20))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            if let message {
                Text(message)
                    .font(.callout)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            if isWorking {
                ProgressView()
                    .frame(maxWidth: .infinity, minHeight: 44)
            } else {
                NativeOnboardingPrimaryButton(title: actionTitle, action: action)
                    .disabled(!hasAgreed)
            }
        }
    }
}

struct NativeOnboardingProgressLayout<Content: View>: View {
    let stepIndex: Int32
    let stepCount: Int32
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(spacing: 28) {
            if stepCount > 0 {
                ProgressView(
                    value: Double(stepIndex + 1),
                    total: Double(stepCount)
                )
            }
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
    let instructionsTitle: String
    let connectionNotice: String
    let privacyTitle: String
    let privacyPolicyUrl: String?
    let termsTitle: String
    let termsUrl: String?
    let onInstructions: () -> Void
    let onSubmit: () -> Void
    @ViewBuilder var content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text(title)
                    .font(.largeTitle.bold())
                Text(description)
                    .foregroundStyle(.secondary)
                Button(instructionsTitle, action: onInstructions)
                    .disabled(isSubmitting)
                VStack(alignment: .leading, spacing: 4) {
                    Text(connectionNotice)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    HStack(spacing: 20) {
                        if let value = privacyPolicyUrl, let url = URL(string: value) {
                            Link(privacyTitle, destination: url)
                                .frame(minHeight: 44)
                        }
                        if let value = termsUrl, let url = URL(string: value) {
                            Link(termsTitle, destination: url)
                                .frame(minHeight: 44)
                        }
                    }
                    .font(.footnote)
                }
                content()
            }
            .padding(24)
            .frame(maxWidth: 608, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .navigationBarTitleDisplayMode(.inline)
        .background(Color(uiColor: .systemGroupedBackground))
        .safeAreaInset(edge: .bottom, spacing: 0) {
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
            .padding(24)
            .frame(maxWidth: 608)
            .frame(maxWidth: .infinity)
            .background(Color(uiColor: .systemGroupedBackground))
        }
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
        VStack(alignment: .trailing, spacing: 8) {
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
            Button(action: onPaste) {
                Label(pasteTitle, systemImage: "doc.on.clipboard")
                    .padding(.vertical, 8)
            }
            .disabled(!enabled)
        }
        .onDisappear {
            visible = false
            focused = false
        }
    }
}
