import SwiftUI

enum NativeShellStage {
    case loading
    case onboarding
    case tabs
}

final class NativeShellObserver<Value> {
    private let onChange: (Value) -> Void

    init(_ onChange: @escaping (Value) -> Void) {
        self.onChange = onChange
    }

    func send(_ value: Value) {
        onChange(value)
    }
}

struct NativeShellAdapter {
    let initialStage: () -> NativeShellStage
    let initialSelectedTab: () -> String
    let selectTab: (String) -> Void
    let observeStage: (NativeShellObserver<NativeShellStage>) -> (() -> Void)
    let observeSelectedTab: (NativeShellObserver<String>) -> (() -> Void)
    let observeTheme: (NativeShellObserver<String>) -> (() -> Void)
    let observeTabTitles: (NativeShellObserver<[String: String]>) -> (() -> Void)
    let nativeScanView: () -> AnyView
    let nativeHubView: () -> AnyView
    let nativeSettingsView: () -> AnyView
    let nativeOnboardingView: () -> AnyView
}

private enum AppShellTab: String, CaseIterable, Identifiable {
    case scan
    case hub
    case settings

    var id: String { rawValue }

    var symbol: String {
        switch self {
        case .scan: return "qrcode.viewfinder"
        case .hub: return "square.grid.2x2"
        case .settings: return "gearshape"
        }
    }

}

@MainActor
private final class NativeShellModel: ObservableObject {
    let tabs: [AppShellTab]
    @Published var stage: NativeShellStage
    @Published var tabTitles: [String: String] = [:]
    @Published var preferredColorScheme: ColorScheme?
    @Published private(set) var selectedTab: String

    private let adapter: NativeShellAdapter
    private var cancels: [() -> Void] = []
    // Each screen is built at most once, on first visit, so cold launch never initializes
    // offscreen tabs (Hub, Settings) or onboarding controllers, while keeping stable view identity
    // across tab switches.
    private var tabViews: [String: AnyView] = [:]
    private var loadedTabs: Set<String> = []
    private var onboardingView: AnyView?

    init(adapter: NativeShellAdapter) {
        self.adapter = adapter
        let visibleTabs = AppShellTab.allCases
        tabs = visibleTabs
        let initialStage = adapter.initialStage()
        stage = initialStage
        let initialTab = adapter.initialSelectedTab()
        let resolvedTab = tabs.contains { $0.id == initialTab } ? initialTab : AppShellTab.scan.id
        selectedTab = resolvedTab
        if initialStage == .tabs {
            loadedTabs.insert(resolvedTab)
        }

        cancels.append(adapter.observeStage(NativeShellObserver { [weak self] stage in
            guard let self else { return }
            if stage == .tabs {
                loadedTabs.insert(selectedTab)
            }
            if stage != .onboarding {
                onboardingView = nil
            }
            self.stage = stage
        }))
        cancels.append(adapter.observeSelectedTab(NativeShellObserver { [weak self] tab in
            guard let self else { return }
            loadedTabs.insert(tab)
            guard selectedTab != tab else { return }
            selectedTab = tab
        }))
        cancels.append(adapter.observeTheme(NativeShellObserver { [weak self] value in
            self?.preferredColorScheme = Self.colorScheme(value)
        }))
        cancels.append(adapter.observeTabTitles(NativeShellObserver { [weak self] titles in
            self?.tabTitles = titles
        }))
    }

    deinit {
        cancels.forEach { $0() }
    }

    func selectTab(_ tab: String) {
        loadedTabs.insert(tab)
        adapter.selectTab(tab)
    }

    func title(_ tab: AppShellTab) -> String {
        tabTitles[tab.id] ?? ""
    }

    var hasTabTitles: Bool {
        tabs.allSatisfy { tabTitles[$0.id] != nil }
    }

    func isLoaded(_ tab: AppShellTab) -> Bool {
        loadedTabs.contains(tab.id) || selectedTab == tab.id
    }

    func view(for tab: AppShellTab) -> AnyView {
        if let existing = tabViews[tab.id] {
            return existing
        }
        let created: AnyView
        switch tab {
        case .scan: created = adapter.nativeScanView()
        case .hub: created = adapter.nativeHubView()
        case .settings: created = adapter.nativeSettingsView()
        }
        tabViews[tab.id] = created
        return created
    }

    func nativeOnboardingView() -> AnyView {
        if let existing = onboardingView {
            return existing
        }
        let created = adapter.nativeOnboardingView()
        onboardingView = created
        return created
    }

    private static func colorScheme(_ value: String) -> ColorScheme? {
        switch value {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }
}

struct NativeAppShell: View {
    @StateObject private var model: NativeShellModel

    init(adapter: NativeShellAdapter) {
        _model = StateObject(wrappedValue: NativeShellModel(adapter: adapter))
    }

    var body: some View {
        Group {
            switch model.stage {
            case .tabs:
                if model.hasTabTitles {
                    TabView(selection: Binding(
                        get: { model.selectedTab },
                        set: model.selectTab
                    )) {
                        ForEach(model.tabs) { tab in
                            // No whole-screen `privacySensitive()`. Redacting a whole tab means any
                            // active privacy redaction blanks the entire screen; apply it to the
                            // individual sensitive value instead.
                            Group {
                                if model.isLoaded(tab) {
                                    model.view(for: tab)
                                } else {
                                    Color.clear
                                }
                            }
                            .environment(
                                \.appShellTabIsSelected,
                                model.selectedTab == tab.id
                            )
                            .tabItem {
                                Label(model.title(tab), systemImage: tab.symbol)
                            }
                            .tag(tab.id)
                        }
                    }
                } else {
                    ProgressView()
                }
            case .onboarding:
                model.nativeOnboardingView()
            case .loading:
                ProgressView()
            }
        }
        .preferredColorScheme(model.preferredColorScheme)
    }
}
