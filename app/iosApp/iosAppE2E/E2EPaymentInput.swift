import Foundation
import Shared

/// The E2E app (qa/test-plan.md §6.8). Maestro's `launchApp` arguments reach iOS as launch
/// arguments (`-key value`), which UserDefaults reads:
/// - `scan`: what the payment screen "reads" once; the camera is never used.
/// - `blinkApiUrl`: the Blink backend, by default the local Blink stack (`tools/blink-stack`).
enum E2EPaymentInput {
    static func install() {
        let arguments = UserDefaults.standard
        E2eHooksKt.installE2eScanInput(value: arguments.string(forKey: "scan"))
        E2eHooksKt.installE2eBlinkApi(
            serverUrl: arguments.string(forKey: "blinkApiUrl") ?? "http://127.0.0.1:4475/graphql"
        )
    }
}
