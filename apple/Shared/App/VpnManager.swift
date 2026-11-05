// macos/GostX/VpnManager.swift
import Foundation
import Libgost
import NetworkExtension
import os

/// 管理 NETunnelProviderManager 生命周期：加载/保存 VPN 配置、启停连接。
@MainActor
class VpnManager: ObservableObject {
    static let shared = VpnManager()

    @Published var status: NEVPNStatus = .invalid

    /// Last configuration/start failure, surfaced by the UI so NEVPNErrorDomain
    /// codes are not swallowed.
    @Published var lastError: String?

    private let tunnelBundleId = "cn.liukebin.gostx.tunnel"
    private var manager: NETunnelProviderManager?
    private var statusObserver: NSObjectProtocol?

    private init() {}

    // MARK: - Setup

    /// 首次调用时加载已存在的 VPN 配置，或创建新的。
    /// 必须在应用启动时调用一次；失败原因会写入 `lastError` 供界面展示。
    func setup() async throws {
        // 已有 manager 则跳过
        if manager != nil { return }

        do {
            let managers = try await NETunnelProviderManager.loadAllFromPreferences()
            if let existing = managers.first(where: {
                ($0.protocolConfiguration as? NETunnelProviderProtocol)?
                    .providerBundleIdentifier == tunnelBundleId
            }) {
                manager = existing
                status = existing.connection.status
            } else {
                let m = NETunnelProviderManager()
                let proto = NETunnelProviderProtocol()
                proto.providerBundleIdentifier = tunnelBundleId
                proto.serverAddress = "GostX"
                m.protocolConfiguration = proto
                m.isEnabled = true
                m.localizedDescription = "GostX VPN"
                try await m.saveToPreferences()
                // Reading back verifies that the system accepted and persisted
                // the configuration; without it startVPNTunnel() can fail with
                // NEVPNErrorConfigurationInvalid even though saving "worked".
                try await m.loadFromPreferences()
                manager = m
            }
            observeStatus()
            lastError = nil
        } catch {
            let description = Self.describe(error)
            os_log(.error, "[GostX] VpnManager.setup failed: %{public}@", description)
            lastError = description
            throw error
        }
    }

    /// NEVPNError codes are opaque in the default localizedDescription, so add
    /// the numeric code and a hint for the ones users actually hit.
    static func describe(_ error: Error) -> String {
        let ns = error as NSError
        guard ns.domain == NEVPNErrorDomain else { return ns.localizedDescription }
        let hint: String
        switch NEVPNError.Code(rawValue: ns.code) {
        case .configurationInvalid:
            hint = NSLocalizedString("The VPN configuration was rejected. Check that the app ID has the Network Extension capability and that the tunnel extension is signed with the same team.", comment: "")
        case .configurationDisabled:
            hint = NSLocalizedString("VPN configurations are disabled on this device. Enable them under Settings > General > VPN & Device Management.", comment: "")
        case .connectionFailed:
            hint = NSLocalizedString("The system could not start the VPN connection.", comment: "")
        case .configurationStale:
            hint = NSLocalizedString("The saved VPN configuration is stale. Delete the GostX VPN profile and try again.", comment: "")
        default:
            hint = ns.localizedDescription
        }
        return "\(hint) (NEVPNErrorDomain \(ns.code))"
    }

    // MARK: - Control

    func start() async throws {
        if manager == nil {
            try await setup()
        }
        guard let m = manager else {
            os_log(.error, "[GostX] VpnManager.start: manager is nil")
            return
        }

        // The extension only reads the App Group, so the active config must be
        // written before the tunnel starts; otherwise it runs the previous one.
        let yaml = ConfigRepository.shared.activeConfig
        guard yaml.contains("tungo") else {
            throw NSError(
                domain: "GostX",
                code: 10,
                userInfo: [NSLocalizedDescriptionKey: NSLocalizedString(
                    "This config has no tungo service and can't be used on iOS.", comment: "")]
            )
        }
        let validation = LibgostValidateConfig(yaml)
        guard validation.isEmpty else {
            throw NSError(
                domain: "GostX",
                code: 11,
                userInfo: [NSLocalizedDescriptionKey: validation]
            )
        }
        AppGroupConfig.writeYaml(yaml)
        os_log(.default, "[GostX] VpnManager.start: calling startVPNTunnel, status=%d", m.connection.status.rawValue)
        try m.connection.startVPNTunnel()
        os_log(.default, "[GostX] VpnManager.start: startVPNTunnel returned, status=%d", m.connection.status.rawValue)
    }

    func stop() {
        manager?.connection.stopVPNTunnel()
    }

    /// Stops the tunnel and waits until it reaches `.disconnected` or `.invalid`.
    func stopAndWait() async {
        guard let conn = manager?.connection else { return }

        // Already stopped — nothing to do.
        if conn.status == .disconnected || conn.status == .invalid { return }

        stop()

        // Poll for disconnection with a timeout so we never hang.
        for _ in 0..<50 {  // 5 seconds max
            try? await Task.sleep(nanoseconds: 100_000_000)  // 100ms
            if conn.status == .disconnected || conn.status == .invalid { return }
        }
        os_log(.error, "[GostX] VpnManager.stopAndWait: timed out waiting for disconnect, status=%d", conn.status.rawValue)
    }

    // MARK: - Status observation

    private func observeStatus() {
        statusObserver = NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange,
            object: manager?.connection,
            queue: .main
        ) { [weak self] _ in
            guard let self, let m = self.manager else { return }
            self.status = m.connection.status
        }
    }

    deinit {
        if let observer = statusObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }
}
