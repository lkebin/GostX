import SwiftUI

@main
struct GostXApp: App {
    @StateObject private var vpn = VpnManager.shared

    var body: some Scene {
        WindowGroup {
            HomeView()
                .task {
                    // Hosted unit tests launch the app; they have no Network
                    // Extension permission and do not need one.
                    if ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil {
                        await vpn.setup()
                    }
                }
        }
    }
}
