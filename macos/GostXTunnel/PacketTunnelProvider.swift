// macos/GostXTunnel/PacketTunnelProvider.swift
import NetworkExtension
import Libgost
import os

class PacketTunnelProvider: NEPacketTunnelProvider {

    override func startTunnel(options: [String: NSObject]?) async throws {
        os_log(.default, "[GostX] startTunnel called")

        // 1. 设置工作目录和日志
        setWorkDirAndLog()
        os_log(.default, "[GostX] workDir and log set, containerURL=%@", AppGroupConfig.containerURL?.path ?? "nil")

        // 2. 从 App Group 读取 YAML 配置
        let yaml = AppGroupConfig.readYaml()
        os_log(.default, "[GostX] yaml read, length=%d", yaml?.count ?? 0)
        guard let yaml = yaml, !yaml.isEmpty else {
            os_log(.error, "[GostX] no YAML config")
            cancelTunnelWithError(NSError(
                domain: "GostXTunnel",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "No YAML config in App Group"]
            ))
            return
        }

        // 3. 启动 gost 服务
        os_log(.default, "[GostX] starting gost...")
        var err: NSError?
        let ok = LibgostStartGost(yaml, "", &err)
        os_log(.default, "[GostX] StartGost returned ok=%d err=%{public}@", ok, err?.localizedDescription ?? "nil")
        if !ok, let err {
            cancelTunnelWithError(NSError(
                domain: "GostXTunnel.startGost",
                code: 2,
                userInfo: [NSLocalizedDescriptionKey: err.localizedDescription]
            ))
            return
        }

        // 4. 配置 TUN 网络设置
        os_log(.default, "[GostX] setting network settings...")
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")
        settings.mtu = 1500
        // Both 10.0.0.2 and 10.0.0.3 must be registered so the kernel
        // accepts packets the system stack rewrites (source→10.0.0.3).
        let ipv4 = NEIPv4Settings(addresses: ["10.0.0.2", "10.0.0.3"], subnetMasks: ["255.255.255.0", "255.255.255.0"])
        ipv4.includedRoutes = [NEIPv4Route.default()]
        settings.ipv4Settings = ipv4
        settings.dnsSettings = NEDNSSettings(servers: ["10.0.0.3"])

        do {
            try await setTunnelNetworkSettings(settings)
            os_log(.default, "[GostX] network settings applied")
        } catch {
            os_log(.error, "[GostX] setTunnelNetworkSettings failed: %@", error.localizedDescription)
            LibgostStopGost(nil)
            cancelTunnelWithError(NSError(
                domain: "GostXTunnel.setNetworkSettings",
                code: 3,
                userInfo: [NSLocalizedDescriptionKey: error.localizedDescription]
            ))
            return
        }

        // 5. 扫描找到 utun fd
        let tunFd = LibgostGetTunnelFileDescriptor()
        os_log(.default, "[GostX] tunFd=%d", tunFd)
        guard tunFd != -1 else {
            LibgostStopGost(nil)
            cancelTunnelWithError(NSError(
                domain: "GostXTunnel",
                code: 4,
                userInfo: [NSLocalizedDescriptionKey: "Cannot locate TUN file descriptor"]
            ))
            return
        }

        // 6. 启动 TUN stack
        os_log(.default, "[GostX] starting TUN stack...")
        if !LibgostStartTun(Int(tunFd), 1500, &err), let err {
            os_log(.error, "[GostX] StartTun failed: %@", err.localizedDescription)
            LibgostStopGost(nil)
            cancelTunnelWithError(NSError(
                domain: "GostXTunnel.startTun",
                code: 5,
                userInfo: [NSLocalizedDescriptionKey: err.localizedDescription]
            ))
            return
        }
        os_log(.default, "[GostX] tunnel started successfully!")
    }

    override func stopTunnel(with reason: NEProviderStopReason) async {
        os_log(.default, "[GostX] stopTunnel, reason=%d", reason.rawValue)
        LibgostStopTun(nil)
        LibgostStopGost(nil)
    }

    private func setWorkDirAndLog() {
        guard let containerURL = AppGroupConfig.containerURL else {
            os_log(.error, "[GostX] containerURL is nil!")
            return
        }
        let filesDir = containerURL.appendingPathComponent("files")
        try? FileManager.default.createDirectory(at: filesDir,
            withIntermediateDirectories: true, attributes: nil)
        LibgostSetWorkDir(filesDir.path, nil)

        // Read logging preferences from shared UserDefaults
        let level = AppGroupConfig.loggingEnabled ? AppGroupConfig.logLevel : "off"
        let logFile = containerURL.appendingPathComponent("gost.log").path
        LibgostSetLogMaxSize(2 * 1024 * 1024)
        LibgostSetLogFile(logFile, nil)
        LibgostSetLogLevel(level)
        os_log(.default, "[GostX] logging: enabled=%{public}@ level=%{public}@",
               AppGroupConfig.loggingEnabled ? "yes" : "no", level)
    }
}
