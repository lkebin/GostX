// macos/GostX/VpnManager.swift
import Foundation
import NetworkExtension
import os

/// 管理 NETunnelProviderManager 生命周期：加载/保存 VPN 配置、启停连接。
@MainActor
class VpnManager: ObservableObject {
    static let shared = VpnManager()

    @Published var status: NEVPNStatus = .invalid

    private let tunnelBundleId = "cn.liukebin.gostx.tunnel"
    private var manager: NETunnelProviderManager?
    private var statusObserver: NSObjectProtocol?

    private init() {}

    // MARK: - Setup

    /// 首次调用时加载已存在的 VPN 配置，或创建新的。
    /// 必须在应用启动时调用一次。
    func setup() async {
        // 已有 manager 则跳过
        if manager != nil { return }

        let managers = (try? await NETunnelProviderManager.loadAllFromPreferences()) ?? []
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
            try? await m.saveToPreferences()
            manager = m
        }
        observeStatus()
    }

    // MARK: - Control

    func start() async throws {
        guard let m = manager else {
            os_log(.error, "[GostX] VpnManager.start: manager is nil")
            return
        }
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
