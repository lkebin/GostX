// macos/GostXTunnel/PacketTunnelProvider.swift
import NetworkExtension
import Libgost

class PacketTunnelProvider: NEPacketTunnelProvider {

    override func startTunnel(options: [String: NSObject]?) async throws {
        // 1. 设置工作目录和日志
        setWorkDirAndLog()

        // 2. 从 App Group 读取 YAML 配置
        guard let yaml = AppGroupConfig.readYaml(), !yaml.isEmpty else {
            cancelTunnelWithError(NSError(
                domain: "GostXTunnel",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "No YAML config in App Group"]
            ))
            return
        }

        // 3. 启动 gost 服务
        do {
            try LibgostStartGost(yaml, "")
        } catch {
            cancelTunnelWithError(NSError(
                domain: "GostXTunnel.startGost",
                code: 2,
                userInfo: [NSLocalizedDescriptionKey: error.localizedDescription]
            ))
            return
        }

        // 4. 配置 TUN 网络设置（系统自动创建 utun 接口）
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "10.0.0.1")
        settings.mtu = 1500

        let ipv4 = NEIPv4Settings(
            addresses: ["10.0.0.2"],
            subnetMasks: ["255.255.255.0"]
        )
        ipv4.includedRoutes = [NEIPv4Route.default()]
        settings.ipv4Settings = ipv4

        settings.dnsSettings = NEDNSSettings(servers: ["10.0.0.3"])

        do {
            try await setTunnelNetworkSettings(settings)
        } catch {
            LibgostStopGost()
            cancelTunnelWithError(NSError(
                domain: "GostXTunnel.setNetworkSettings",
                code: 3,
                userInfo: [NSLocalizedDescriptionKey: error.localizedDescription]
            ))
            return
        }

        // 5. 扫描找到 utun fd
        let tunFd = LibgostGetTunnelFileDescriptor()
        guard tunFd != -1 else {
            LibgostStopGost()
            cancelTunnelWithError(NSError(
                domain: "GostXTunnel",
                code: 4,
                userInfo: [NSLocalizedDescriptionKey: "Cannot locate TUN file descriptor"]
            ))
            return
        }

        // 6. 启动 TUN stack（sing-tun 直接在 fd 上读写）
        do {
            try LibgostStartTun(Int(tunFd), 1500)
        } catch {
            LibgostStopGost()
            cancelTunnelWithError(NSError(
                domain: "GostXTunnel.startTun",
                code: 5,
                userInfo: [NSLocalizedDescriptionKey: error.localizedDescription]
            ))
            return
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason) async {
        try? LibgostStopTun()
        try? LibgostStopGost()
    }

    // MARK: - Private

    private func setWorkDirAndLog() {
        guard let containerURL = AppGroupConfig.containerURL else { return }
        let workDir = containerURL.path
        LibgostSetWorkDir(workDir)

        let logFile = containerURL.appendingPathComponent("gost.log").path
        LibgostSetLogFile(logFile)
        LibgostSetLogLevel("info")
    }
}
