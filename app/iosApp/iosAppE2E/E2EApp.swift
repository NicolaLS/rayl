import SwiftUI
import Security
import Shared

@main
struct E2EApp: App {
    init() {
        seedWalletLifecycleFixture()
        configureRaylSpark()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

// Only compiled into the separate E2E app. These fixtures never open a funded wallet.
private func seedWalletLifecycleFixture() {
    let arguments = ProcessInfo.processInfo.arguments
    let configured = arguments.contains("--spark-corrupt-configured")
    guard configured || arguments.contains("--spark-orphan") else { return }
    let defaults = UserDefaults.standard
    defaults.set(true, forKey: "rayl.welcome")
    if configured {
        defaults.set("Spark", forKey: "rayl.wallet")
    } else {
        defaults.removeObject(forKey: "rayl.wallet")
    }
    let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "xyz.lilsus.rayl.providers.spark.wallet",
        kSecAttrAccount as String: "singleton-mnemonic-v1"
    ]
    let deletion = SecItemDelete(query as CFDictionary)
    precondition(deletion == errSecSuccess || deletion == errSecItemNotFound, "Test credential deletion failed: \(deletion)")
    var attributes = query
    attributes[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
    attributes[kSecValueData as String] = Data("invalid test recovery phrase".utf8)
    let insertion = SecItemAdd(attributes as CFDictionary, nil)
    precondition(insertion == errSecSuccess, "Test credential insertion failed: \(insertion)")
}
