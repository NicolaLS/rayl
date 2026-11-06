import Foundation
import Shared

/// The E2E app never uses the camera. Maestro's `launchApp` arguments reach iOS as launch
/// arguments (`-scan <value>`), which UserDefaults reads: `scan` is what the payment screen
/// "reads" once (qa/test-plan.md §6.8).
enum E2EPaymentInput {
    static func install() {
        E2eScanInputKt.installE2eScanInput(value: UserDefaults.standard.string(forKey: "scan"))
    }
}
