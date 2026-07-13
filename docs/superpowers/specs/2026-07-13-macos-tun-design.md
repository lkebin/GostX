# macOS TUN 支持 — 设计文档

日期: 2026-07-13

## 目标

为 GostX macOS 客户端添加 TUN VPN 支持：VPN 启动后，系统所有流量经 utun 接口通过 gost chain 转发。

## 架构概览

```
GostX.app
├── 主 App (SwiftUI 状态栏)
│   ├── VpnManager.swift          # NETunnelProviderManager 启停
│   ├── AppDelegate.swift         # VPN 模式 + 旧代理模式
│   ├── SettingsView.swift        # YAML 编辑（同步到 App Group）
│   └── MacExtrasConfigurator.swift
│
├── GostXTunnel.appex (PacketTunnelProvider Extension)
│   └── PacketTunnelProvider.swift
│       ├── startTunnel:
│       │   1. 从 App Group 读 YAML → StartGost(yaml, "")
│       │   2. setTunnelNetworkSettings → 系统创建 utun
│       │   3. GetTunnelFileDescriptor() → 扫描 fd 找 utun
│       │   4. StartTun(fd, 1500) → sing-tun 直接 fd 读写
│       └── stopTunnel:
│           1. StopTun() → StopGost()
│
└── Frameworks/Libgost.xcframework  # gomobile bind -target macos
```

### App Group 通信

主 App 和 Extension 通过 `group.cn.liukebin.gostx` 共享：
- **YAML 配置**：主 App 写入 → Extension 读取
- **日志文件**：Extension 写入 → 主 App 读取显示

## TUN 数据流

```
Apps → utun → sing-tun system stack (IP header rewrite)
  → OS kernel TCP/IP → singTunHandler (NewConnectionEx)
  → gost chain Router → 上游代理 → 互联网
```

与 Android 完全一致，不需要 `packetFlow.readPackets()/writePackets()` 桥接。Go sing-tun 直接在 utun fd 上读写。

### 路由循环

macOS NetworkExtension 的 `setTunnelNetworkSettings` 配置路由后，系统能区分哪些流量走 utun、哪些不走。Extension 进程自身创建的 upstream socket 不会被自己的 VPN 路由捕获，**不需要** Android 的 `protect(fd)` 机制。

## Go 层改动

### 新增：`tun_darwin.go` — utun fd 发现

```go
// +build darwin

package libgost

import "golang.org/x/sys/unix"

// GetTunnelFileDescriptor 扫描 fd 0..1024，找到 com.apple.net.utun_control
// 控制 socket 并返回其 fd。返回 -1 表示未找到。
// 实现参考 wireguard-apple 的 WireGuardAdapter.tunnelFileDescriptor。
func GetTunnelFileDescriptor() int32 {
    ctlInfo := &unix.CtlInfo{}
    copy(ctlInfo.Name[:], "com.apple.net.utun_control")
    for fd := int32(0); fd < 1024; fd++ {
        addr, err := unix.Getpeername(int(fd))
        if err != nil {
            continue
        }
        addrCTL, ok := addr.(*unix.SockaddrCtl)
        if !ok {
            continue
        }
        if ctlInfo.Id == 0 {
            if err = unix.IoctlCtlInfo(int(fd), ctlInfo); err != nil {
                continue
            }
        }
        if addrCTL.ID == ctlInfo.Id {
            return fd
        }
    }
    return -1
}
```

### 不变（零改动）

| 函数 | 说明 |
|------|------|
| `StartTun(fd int, mtu int) error` | 已支持 Unix fd + sing-tun，不区分平台 |
| `StopTun() error` | 同上 |
| `StartGost(yaml, systemDNS string) error` | systemDNS 传空字符串即可 |
| `StopGost() error` | 同上 |
| `relay()` / `relayPacketConn()` / `singTunHandler` | 全部不变 |

### macOS 上 no-op

- `SetMemoryLimit(bool)` — macOS Extension 不需要控 GC，不调用或内部 no-op
- `SetSocketProtector(SocketProtector)` — macOS 不需要 protect，不调用或内部 no-op

## 编译 & 构建

### gomobile bind

```bash
# libgost/Makefile 新增
macos-framework:
    CGO_ENABLED=1 gomobile bind \
      -target macos \
      -trimpath -ldflags="-s -w" \
      -o ../macos/Frameworks/Libgost.xcframework \
      .
```

产物 `Libgost.xcframework` 包含 `macos-arm64_x86_64/`。

Go 函数自动映射为 Swift 调用（包名 `libgost` → 前缀 `Libgost`）：

| Go | Swift |
|----|-------|
| `StartTun(fd, mtu) error` | `try LibgostStartTun(_ fd: Int, _ mtu: Int)` |
| `StartGost(yaml, dns) error` | `try LibgostStartGost(_ yaml: String, _ dns: String)` |
| `StopTun() error` | `try LibgostStopTun()` |
| `GetTunnelFileDescriptor() int32` | `LibgostGetTunnelFileDescriptor() -> Int32` |

## Swift 层改动

### PacketTunnelProvider Extension（新增 `macos/GostXTunnel/`）

核心文件 `PacketTunnelProvider.swift`：

```swift
import NetworkExtension

class PacketTunnelProvider: NEPacketTunnelProvider {

    override func startTunnel(options: [String: NSObject]?) async throws {
        // 1. 从 App Group 读 YAML
        let yaml = loadYamlFromAppGroup()

        // 2. 设置日志
        LibgostSetLogFile(logFilePath)
        LibgostSetLogLevel("info")

        // 3. 启动 gost 服务
        try LibgostStartGost(yaml, "")

        // 4. 配置 TUN 路由（系统自动创建 utun）
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "10.0.0.1")
        settings.mtu = 1500
        settings.ipv4Settings = NEIPv4Settings(
            addresses: ["10.0.0.2"],
            subnetMasks: ["255.255.255.0"]
        )
        settings.ipv4Settings!.includedRoutes = [NEIPv4Route.default()]
        settings.dnsSettings = NEDNSSettings(servers: ["10.0.0.3"])

        try await setTunnelNetworkSettings(settings)

        // 5. 找到 utun fd
        let tunFd = LibgostGetTunnelFileDescriptor()
        guard tunFd != -1 else {
            LibgostStopGost()
            cancelTunnelWithError(NSError(domain: "cannot locate TUN fd", code: 1))
            return
        }

        // 6. 启动 TUN stack
        try LibgostStartTun(Int(tunFd), 1500)
    }

    override func stopTunnel(with reason: NEProviderStopReason) async {
        LibgostStopTun()
        LibgostStopGost()
    }
}
```

### 主 App（修改 `macos/GostX/`）

**新增 `VpnManager.swift`：**

```swift
import NetworkExtension

class VpnManager: ObservableObject {
    @Published var state: NEVPNStatus = .invalid
    private var manager: NETunnelProviderManager?

    func loadAndSave() async {
        let managers = await NETunnelProviderManager.loadAllFromPreferences()
        manager = managers.first ?? NETunnelProviderManager()
        let proto = NETunnelProviderProtocol()
        proto.providerBundleIdentifier = "cn.liukebin.gostx.tunnel"
        proto.serverAddress = "GostX"
        manager?.protocolConfiguration = proto
        manager?.isEnabled = true
        try? await manager?.saveToPreferences()
    }

    func start() async throws { try manager?.connection.startVPNTunnel() }
    func stop() { manager?.connection.stopVPNTunnel() }
}
```

**AppDelegate 改动：**
- `start()` 根据模式选择：VPN 模式调 `VpnManager.start()`，代理模式调 `LibgostStartGost(yaml, "")`
- 状态栏菜单新增：VPN 状态 + 启停
- Settings 保存 YAML 时同步写入 App Group 容器
- 旧 `import Gost` 及 `gostRunYaml()`/`gostStop()`/`gostInfo()` 全部移除

### 替换旧依赖

旧 `import Gost`（gost v2 C bridge）完全替换为 `import Libgost`（gomobile xcframework）。
两种模式共用同一个 Go 层：
- **代理模式**（非 VPN）：`LibgostStartGost(yaml)` 启动本地代理
- **VPN 模式**：`LibgostStartGost(yaml)` + `LibgostStartTun(fd, mtu)` 启动全局转发

## Xcode 项目结构

```
macos/
├── GostX.xcodeproj
├── GostX/                          # 主 App target (AppKit)
│   ├── AppDelegate.swift
│   ├── VpnManager.swift           # 新增
│   ├── SettingsView.swift
│   ├── MacExtrasConfigurator.swift
│   ├── Arguments.swift
│   ├── main.swift
│   └── GostX.entitlements
├── GostXTunnel/                    # Extension target (新增)
│   ├── PacketTunnelProvider.swift
│   ├── Info.plist
│   └── GostXTunnel.entitlements
└── Frameworks/                     # 新增
    └── Libgost.xcframework         # gomobile bind 产物
```

## Entitlements

### 主 App

```xml
com.apple.security.app-sandbox → true
com.apple.security.network.client → true
com.apple.security.network.server → true
com.apple.security.application-groups → [group.cn.liukebin.gostx]
```

### Extension

```xml
com.apple.security.app-sandbox → true
com.apple.security.application-groups → [group.cn.liukebin.gostx]
com.apple.security.network.client → true
com.apple.security.network.server → true
com.apple.developer.networking.networkextension → [packet-tunnel-provider]
```

## 错误处理

### 启动失败

各级别分别捕获，失败时 `cancelTunnelWithError`：
1. YAML 读取失败
2. `StartGost` 失败
3. `setTunnelNetworkSettings` 失败 / utun fd 找不到
4. `StartTun` 失败（先 `StopGost` 清理）

### 网络变化

`NWPathMonitor` 监听网络切换，调用 Go 层 `ResetTunConnections()`（已有函数）刷新已有连接。

### 停止

```
StopTun() → StopGost()
```

WireGuard 的 `exit(0)` hack（Apple bug 32073323）先不加，观察 Extension 进程是否能正常回收，如有问题再补。

### Extension 崩溃

系统自动清理 utun 接口。主 App 通过 `NEVPNStatusDidChange` 更新 UI。

## 风险 & 缓解

| 风险 | 缓解 |
|------|------|
| Extension 内存限制 | sing-tun 使用 kernel system stack（非 userspace TCP），内存负担小；暂不设 GC 限制，观察 |
| `gomobile bind -target macos` 兼容性 | Apple Silicon 天然支持 arm64；Intel Mac 需 x86_64，gomobile 默认双架构 |
| utun fd 扫描失败 | 概率极低（WireGuard 至今线上一贯用这个方案）；返回清晰错误信息 |
| Go 依赖过多致 framework 体积大 | `-ldflags="-s -w"` 去符号；xcframework 切片按架构分 |
