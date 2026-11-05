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
                    // Same ring glyph as the Android FAB and the macOS menu bar
                    // icon, generated from icon/ring.svg by `make icon`.
                    Image("ConnectRing")
                        .renderingMode(.template)
                        .resizable()
                        .frame(width: 33, height: 33)
                        .foregroundStyle(foreground)
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(status.isTransitioning)
        .accessibilityLabel(status.isConnected ? "Disconnect" : "Connect")
    }
}
