import SwiftUI
import NetworkExtension

extension NEVPNStatus {
    var isTransitioning: Bool {
        self == .connecting || self == .reasserting || self == .disconnecting
    }

    var isConnected: Bool { self == .connected }
}

/// Circular connect button mirroring the Android floating action button.
struct ConnectButton: View {
    let status: NEVPNStatus
    let action: () -> Void

    private var background: Color {
        status.isConnected ? .accentColor : Color.accentColor.opacity(0.2)
    }

    private var foreground: Color {
        status.isConnected ? .white : .accentColor
    }

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .fill(background)
                    .frame(width: 72, height: 72)
                if status.isTransitioning {
                    ProgressView().tint(foreground)
                } else {
                    Image(systemName: "power")
                        .font(.system(size: 30, weight: .semibold))
                        .foregroundStyle(foreground)
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(status.isTransitioning)
        .accessibilityLabel(status.isConnected ? "Disconnect" : "Connect")
    }
}
