import AppIntents
import Foundation

/// Bridges App Intents (Shortcuts, Control Centre on iOS 18, the Action
/// button) to the main-actor VPN manager.
@MainActor
enum VpnIntentBridge {
    static func connect() async throws {
        try await VpnManager.shared.start()
    }

    static func disconnect() {
        VpnManager.shared.stop()
    }

    static func toggle() async throws {
        let vpn = VpnManager.shared
        if vpn.status.isConnected {
            vpn.stop()
        } else {
            try await vpn.start()
        }
    }
}

/// `openAppWhenRun` is true so the tunnel start always happens with the app
/// foregrounded; iOS is unreliable about starting a tunnel from the
/// background. Flip it to false once on-device testing proves otherwise.
struct ConnectVPNIntent: AppIntent {
    static var title: LocalizedStringResource = "Connect GostX"
    static var description = IntentDescription("Starts the GostX VPN tunnel.")
    static var openAppWhenRun: Bool = true

    func perform() async throws -> some IntentResult {
        try await VpnIntentBridge.connect()
        return .result()
    }
}

struct DisconnectVPNIntent: AppIntent {
    static var title: LocalizedStringResource = "Disconnect GostX"
    static var description = IntentDescription("Stops the GostX VPN tunnel.")
    static var openAppWhenRun: Bool = true

    func perform() async throws -> some IntentResult {
        await VpnIntentBridge.disconnect()
        return .result()
    }
}

struct ToggleVPNIntent: AppIntent {
    static var title: LocalizedStringResource = "Toggle GostX"
    static var description = IntentDescription("Connects or disconnects the GostX VPN tunnel.")
    static var openAppWhenRun: Bool = true

    func perform() async throws -> some IntentResult {
        try await VpnIntentBridge.toggle()
        return .result()
    }
}

/// Surfaces the intents in the Shortcuts app without a user-created shortcut.
struct GostXShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: ToggleVPNIntent(),
            phrases: [
                "Toggle \(.applicationName)",
                "切换 \(.applicationName) 连接",
            ],
            shortTitle: "Toggle VPN",
            systemImageName: "circle.circle"
        )
        AppShortcut(
            intent: ConnectVPNIntent(),
            phrases: [
                "Connect \(.applicationName)",
                "连接 \(.applicationName)",
            ],
            shortTitle: "Connect",
            systemImageName: "circle.circle"
        )
        AppShortcut(
            intent: DisconnectVPNIntent(),
            phrases: [
                "Disconnect \(.applicationName)",
                "断开 \(.applicationName)",
            ],
            shortTitle: "Disconnect",
            systemImageName: "circle.slash"
        )
    }
}
