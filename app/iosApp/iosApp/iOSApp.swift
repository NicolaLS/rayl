import SwiftUI
import Shared

@main
struct iOSApp: App {
    init() {
        configureRaylSpark()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
