//  Shared/Common/TunnelStopReason.swift
import Foundation
import NetworkExtension

/// Records *why* the tunnel extension stopped.
///
/// `NEVPNConnection` only ever reports `.disconnected` to the app, so without
/// this the reason a tunnel went down — the extension was killed for memory,
/// the network vanished, the configuration was removed — is invisible. That is
/// exactly the blind spot that made an Android self-stop look like a crash:
/// the user sees "disconnected" and has no way to tell why.
///
/// The extension only stores the raw reason code; the app turns it into a
/// localized sentence. Localizing inside the extension would be pointless —
/// the extension bundle carries no string catalog.
enum TunnelStopReason {
    static let fileName = "tunnel_stop.txt"

    /// One-line explanation worth showing the user, or nil for reasons that
    /// need none (the user stopped it themselves, the device just slept).
    static func message(for reason: NEProviderStopReason) -> String? {
        switch reason {
        case .none, .userInitiated, .authenticationCanceled, .sleep:
            return nil
        case .providerFailed:
            return NSLocalizedString(
                "The tunnel was stopped by the system (it failed or ran out of memory).",
                comment: "Tunnel stop reason")
        case .noNetworkAvailable:
            return NSLocalizedString(
                "The tunnel stopped because no network was available.",
                comment: "Tunnel stop reason")
        case .unrecoverableNetworkChange:
            return NSLocalizedString(
                "The network changed and the tunnel could not recover.",
                comment: "Tunnel stop reason")
        case .providerDisabled, .configurationDisabled:
            return NSLocalizedString(
                "VPN was turned off in Settings.",
                comment: "Tunnel stop reason")
        case .configurationRemoved, .configurationFailed:
            return NSLocalizedString(
                "The GostX VPN configuration was removed or is invalid. Create it again from the app.",
                comment: "Tunnel stop reason")
        case .idleTimeout:
            return NSLocalizedString(
                "The tunnel was stopped after being idle.",
                comment: "Tunnel stop reason")
        case .connectionFailed:
            return NSLocalizedString(
                "The system could not keep the tunnel connection alive.",
                comment: "Tunnel stop reason")
        default:
            return String(
                format: NSLocalizedString(
                    "The tunnel was stopped by the system (reason %d).",
                    comment: "Tunnel stop reason"),
                reason.rawValue)
        }
    }

    /// Called from the extension when the tunnel stops.
    static func record(_ reason: NEProviderStopReason) {
        guard let url = AppGroupConfig.containerURL?.appendingPathComponent(fileName) else {
            return
        }
        try? String(reason.rawValue).write(to: url, atomically: true, encoding: .utf8)
    }

    /// Called from the app once the tunnel is back up: a reason recorded before a
    /// successful reconnect must not surface later as a spurious alert.
    static func clear() {
        guard let url = AppGroupConfig.containerURL?.appendingPathComponent(fileName) else {
            return
        }
        try? FileManager.default.removeItem(at: url)
    }

    /// Called from the app: returns the recorded reason once (localized, nil for
    /// the silent ones) and clears it.
    static func takeMessage() -> String? {
        guard let url = AppGroupConfig.containerURL?.appendingPathComponent(fileName),
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            return nil
        }
        try? FileManager.default.removeItem(at: url)
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let raw = Int(trimmed), let reason = NEProviderStopReason(rawValue: raw) else {
            return nil
        }
        return message(for: reason)
    }
}