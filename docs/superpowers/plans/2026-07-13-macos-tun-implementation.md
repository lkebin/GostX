# macOS TUN 支持 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 GostX macOS 客户端添加 TUN VPN 支持，通过 NEPacketTunnelProvider Extension + sing-tun 实现系统全局流量代理转发。

**Architecture:** 主 App（SwiftUI 状态栏）通过 NETunnelProviderManager 控制 PacketTunnelProvider Extension。Extension 使用 gomobile bind 编译的 Libgost.xcframework，调用 Go 层的 StartGost + StartTun。Go 层通过扫描 fd 找到 utun 控制 socket，sing-tun 直接在 fd 上读写，复用全部现有 Android TUN 代码。

**Tech Stack:** Go (gomobile bind -target macos), Swift (NetworkExtension, AppKit/SwiftUI), sing-tun system stack

## Global Constraints

- Go 层 `StartTun(fd, mtu)` / `StopTun()` / `StartGost()` / `StopGost()` 零改动
- macOS 不需要 `SetSocketProtector`（无路由循环）和 `SetMemoryLimit`（无内存压力）
- utun fd 通过扫描 `/dev/utun` 控制 socket 获取，不使用 KVC 私有 API
- 主 App 和 Extension 通过 App Group `group.cn.liukebin.gostx` 共享 YAML 和日志
- 保留旧代理模式（非 VPN），两种模式共用 Libgost framework

---

### Task 1: Go — utun fd 发现函数

**Files:**
- Create: `libgost/tun_darwin.go`
- Modify: `libgost/tun_test.go`

**Interfaces:**
- Produces: `func GetTunnelFileDescriptor() int32` — 扫描 fd 0..1024 找到 utun 控制 socket，返回 fd 或 -1

- [ ] **Step 1: 创建 `libgost/tun_darwin.go`**

```go
//go:build darwin

package libgost

import (
	"golang.org/x/sys/unix"
)

// GetTunnelFileDescriptor scans file descriptors 0..1024 for the utun
// control socket (com.apple.net.utun_control) and returns its fd.
// Returns -1 if no utun socket is found.
//
// Implementation adapted from wireguard-apple's WireGuardAdapter.tunnelFileDescriptor.
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

- [ ] **Step 2: 添加测试到 `libgost/tun_test.go`**

在文件末尾追加：

```go
import "runtime"

func TestGetTunnelFileDescriptor(t *testing.T) {
	if runtime.GOOS != "darwin" {
		t.Skip("GetTunnelFileDescriptor is darwin-only")
	}
	// When no utun is active (typical unit test env), returns -1 without error.
	fd := GetTunnelFileDescriptor()
	if fd != -1 {
		t.Logf("found existing utun fd: %d", fd)
	}
}
```

- [ ] **Step 3: 运行测试**

```bash
cd libgost && go test -v -run TestGetTunnelFileDescriptor .
```

Expected: PASS (macOS 上返回 -1 或找到已存在的 utun fd)

- [ ] **Step 4: 验证编译**

```bash
cd libgost && GOOS=darwin GOARCH=arm64 go build .
cd libgost && GOOS=darwin GOARCH=amd64 go build .
```

Expected: 编译成功无错误

- [ ] **Step 5: Commit**

```bash
cd /Users/kbliu/Workspace/project/GostX
git add libgost/tun_darwin.go libgost/tun_test.go
git commit -m "feat: add GetTunnelFileDescriptor for macOS utun fd discovery"
```

---

### Task 2: Go — Makefile 新增 macos-framework target

**Files:**
- Modify: `libgost/Makefile`

- [ ] **Step 1: 在 `libgost/Makefile` 末尾追加 macos-framework target**

```makefile
# macOS .xcframework via gomobile bind
# Produces Frameworks/Libgost.xcframework with macos-arm64_x86_64 slices.
# Xcode links this into the PacketTunnelProvider Extension target.
macos-framework:
	CGO_ENABLED=1 GOFLAGS="-buildvcs=false" $(GOMOBILE) bind \
	  -target macos \
	  -trimpath -ldflags="-s -w" \
	  -o Libgost.xcframework \
	  .
	mkdir -p ../macos/Frameworks
	rm -rf ../macos/Frameworks/Libgost.xcframework
	mv Libgost.xcframework ../macos/Frameworks/Libgost.xcframework
	@echo "Libgost.xcframework → macos/Frameworks/"
```

同时更新 `.PHONY` 和 `clean`：

在 `libgost/Makefile` 顶部修改 `.PHONY` 行：

```makefile
.PHONY: all clean debug-symbols macos-framework
```

在 `clean` target 末尾追加：

```makefile
	rm -rf ../macos/Frameworks/Libgost.xcframework
```

- [ ] **Step 2: 构建 xcframework 并验证**

```bash
cd libgost && make macos-framework
ls -la ../macos/Frameworks/Libgost.xcframework/
```

Expected: `macos-arm64_x86_64/` 目录存在，内含 `Libgost.framework`

- [ ] **Step 3: 验证 framework 包含所有 Go 导出函数**

```bash
nm ../macos/Frameworks/Libgost.xcframework/macos-arm64_x86_64/Libgost.framework/Versions/A/Libgost | grep -i "starttun\|stopTun\|startGost\|stopGost\|getTunnelFileDescriptor\|getStatus\|validateConfig\|setLog\|setWorkDir"
```

Expected: 输出包含 `StartTun`、`StopTun`、`StartGost`、`StopGost`、`GetTunnelFileDescriptor` 等符号

- [ ] **Step 4: Commit**

```bash
cd /Users/kbliu/Workspace/project/GostX
git add libgost/Makefile
git commit -m "build: add macos-framework target for gomobile bind -target macos"
```

---

### Task 3: Xcode — 创建 Extension target

**Files:**
- Create: `macos/GostXTunnel/Info.plist`
- Create: `macos/GostXTunnel/GostXTunnel.entitlements`

**Note:** 本 task 创建 Extension 所需的文件。Xcode target 创建（`GostXTunnel`）需要在 Xcode UI 中完成，步骤在最后说明。

- [ ] **Step 1: 创建 `macos/GostXTunnel/Info.plist`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleDisplayName</key>
	<string>GostX Tunnel</string>
	<key>CFBundleName</key>
	<string>$(PRODUCT_NAME)</string>
	<key>CFBundleIdentifier</key>
	<string>$(PRODUCT_BUNDLE_IDENTIFIER)</string>
	<key>CFBundleVersion</key>
	<string>$(CURRENT_PROJECT_VERSION)</string>
	<key>CFBundleShortVersionString</key>
	<string>$(MARKETING_VERSION)</string>
	<key>CFBundlePackageType</key>
	<string>$(PRODUCT_BUNDLE_PACKAGE_TYPE)</string>
	<key>NSExtension</key>
	<dict>
		<key>NSExtensionPointIdentifier</key>
		<string>com.apple.networkextension.packet-tunnel</string>
		<key>NSExtensionPrincipalClass</key>
		<string>$(PRODUCT_MODULE_NAME).PacketTunnelProvider</string>
	</dict>
</dict>
</plist>
```

- [ ] **Step 2: 创建 `macos/GostXTunnel/GostXTunnel.entitlements`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>com.apple.security.app-sandbox</key>
	<true/>
	<key>com.apple.security.application-groups</key>
	<array>
		<string>group.cn.liukebin.gostx</string>
	</array>
	<key>com.apple.security.network.client</key>
	<true/>
	<key>com.apple.security.network.server</key>
	<true/>
	<key>com.apple.developer.networking.networkextension</key>
	<array>
		<string>packet-tunnel-provider</string>
	</array>
</dict>
</plist>
```

- [ ] **Step 3: 在 Xcode 中创建 Extension target**

打开 `macos/GostX.xcodeproj`，按以下步骤操作：

1. **File → New → Target** → 选择 **macOS → Network Extension** → **Packet Tunnel Provider**
2. Product Name: `GostXTunnel`
3. Bundle Identifier: `cn.liukebin.gostx.tunnel`
4. Team: 选择你的 Apple Developer team
5. 删除 Xcode 自动生成的 `PacketTunnelProvider.swift`（将用我们自己写的替换）
6. 右键 GostXTunnel group → **Add Files** → 选择刚创建的 `Info.plist` 和 `GostXTunnel.entitlements`
7. Build Settings → Code Signing Entitlements → 设置为 `GostXTunnel/GostXTunnel.entitlements`
8. Build Settings → Info.plist File → 设置为 `GostXTunnel/Info.plist`
9. Build Phases → Link Binary With Libraries → 点击 + → Add Other → 选择 `macos/Frameworks/Libgost.xcframework`

- [ ] **Step 4: 验证 target 配置**

在 Xcode 中选择 GostXTunnel scheme，按 Cmd+B 构建。

Expected: 构建成功（会有一个空的 PacketTunnelProvider.swift 报错，下一步解决）

- [ ] **Step 5: Commit**

```bash
cd /Users/kbliu/Workspace/project/GostX
git add macos/GostXTunnel/
git add macos/GostX.xcodeproj/
git commit -m "feat: add GostXTunnel PacketTunnelProvider Extension target scaffold"
```

---

### Task 4: Swift — App Group 配置共享辅助类

**Files:**
- Create: `macos/GostX/AppGroupConfig.swift`

**Interfaces:**
- Produces: `struct AppGroupConfig` — 提供 `readYaml()` / `writeYaml(_:)` 静态方法，通过 App Group 容器共享 YAML

- [ ] **Step 1: 创建 `macos/GostX/AppGroupConfig.swift`**

```swift
// macos/GostX/AppGroupConfig.swift
import Foundation

/// 通过 App Group 容器在主 App 和 Extension 之间共享 YAML 配置。
struct AppGroupConfig {
    static let groupId = "group.cn.liukebin.gostx"
    static let yamlFileName = "gost.yaml"

    static var containerURL: URL? {
        FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: groupId
        )
    }

    static var yamlFileURL: URL? {
        containerURL?.appendingPathComponent(yamlFileName)
    }

    /// 从 App Group 容器读取 YAML 配置字符串。
    /// 返回 nil 表示容器不可用或文件不存在。
    static func readYaml() -> String? {
        guard let url = yamlFileURL else { return nil }
        return try? String(contentsOf: url, encoding: .utf8)
    }

    /// 将 YAML 配置写入 App Group 容器。
    /// 失败静默忽略（Extension 启动时会检查并报错）。
    static func writeYaml(_ yaml: String) {
        guard let url = yamlFileURL else { return }
        try? yaml.write(to: url, atomically: true, encoding: .utf8)
    }
}
```

- [ ] **Step 2: 在主 App target 中添加该文件**

1. Xcode → GostX target → Build Phases → Compile Sources → 确认 `AppGroupConfig.swift` 在列表中
2. 同样在 GostXTunnel target → Build Phases → Compile Sources → 添加 `AppGroupConfig.swift`

这样两个 target 都能使用 `AppGroupConfig`。

- [ ] **Step 3: 验证编译**

Xcode 中依次选择 GostX scheme 和 GostXTunnel scheme，Cmd+B 编译。

Expected: 两个 target 均编译成功

- [ ] **Step 4: Commit**

```bash
cd /Users/kbliu/Workspace/project/GostX
git add macos/GostX/AppGroupConfig.swift
git add macos/GostX.xcodeproj/
git commit -m "feat: add AppGroupConfig helper for YAML sharing via App Group"
```

---

### Task 5: Swift — PacketTunnelProvider 实现

**Files:**
- Create: `macos/GostXTunnel/PacketTunnelProvider.swift`

**Interfaces:**
- Consumes: `AppGroupConfig.readYaml()`, `LibgostStartGost()`, `LibgostStartTun()`, `LibgostStopTun()`, `LibgostStopGost()`, `LibgostGetTunnelFileDescriptor()`, `LibgostSetLogFile()`, `LibgostSetLogLevel()`, `LibgostSetWorkDir()`
- Produces: `class PacketTunnelProvider: NEPacketTunnelProvider` — VPN Extension 入口

- [ ] **Step 1: 创建 `macos/GostXTunnel/PacketTunnelProvider.swift`**

```swift
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
        LibgostStopTun()
        LibgostStopGost()
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
```

- [ ] **Step 2: 验证编译**

Xcode → GostXTunnel scheme → Cmd+B

Expected: 编译成功（需要提前 link Libgost.xcframework 到该 target）

- [ ] **Step 3: Commit**

```bash
cd /Users/kbliu/Workspace/project/GostX
git add macos/GostXTunnel/PacketTunnelProvider.swift
git commit -m "feat: implement PacketTunnelProvider with utun TUN stack"
```

---

### Task 6: Swift — VpnManager（主 App VPN 控制）

**Files:**
- Create: `macos/GostX/VpnManager.swift`

- [ ] **Step 1: 创建 `macos/GostX/VpnManager.swift`**

```swift
// macos/GostX/VpnManager.swift
import Foundation
import NetworkExtension
import Combine

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

        let managers = await NETunnelProviderManager.loadAllFromPreferences()
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
        guard let m = manager else { return }
        try m.connection.startVPNTunnel()
    }

    func stop() {
        manager?.connection.stopVPNTunnel()
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
```

- [ ] **Step 2: 验证编译**

Xcode → GostX scheme → Cmd+B

Expected: 编译成功

- [ ] **Step 3: Commit**

```bash
cd /Users/kbliu/Workspace/project/GostX
git add macos/GostX/VpnManager.swift
git commit -m "feat: add VpnManager for macOS VPN lifecycle control"
```

---

### Task 7: Swift — 主 App VPN 集成

**Files:**
- Modify: `macos/GostX/AppDelegate.swift`
- Modify: `macos/GostX/MacExtrasConfigurator.swift`
- Modify: `macos/GostX/SettingsView.swift`
- Modify: `macos/GostX/GostX.entitlements`
- Modify: `macos/GostX/Arguments.swift`

- [ ] **Step 1: 更新 `macos/GostX/GostX.entitlements` — 添加 App Group**

在 `<dict>` 内追加：

```xml
<key>com.apple.security.application-groups</key>
<array>
	<string>group.cn.liukebin.gostx</string>
</array>
```

完整文件变为：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>com.apple.security.app-sandbox</key>
	<true/>
	<key>com.apple.security.network.client</key>
	<true/>
	<key>com.apple.security.network.server</key>
	<true/>
	<key>com.apple.security.application-groups</key>
	<array>
		<string>group.cn.liukebin.gostx</string>
	</array>
</dict>
</plist>
```

- [ ] **Step 2: 更新 `macos/GostX/Arguments.swift` — 添加 App Group key 常量**

在文件末尾追加：

```swift
// YAML 持久化 key（UserDefaults）
let defaultsYamlKey = "gost_yaml_config"

// VPN 模式 key
let defaultsVpnModeKey = "gost_vpn_mode_enabled"

// App Group 容器目录下 YAML 文件名
let appGroupYamlFileName = "gost.yaml"
```

- [ ] **Step 3: 重写 `macos/GostX/AppDelegate.swift`**

完整替换文件内容。将旧 `import Gost` 替换为 `import Libgost`，支持 VPN 模式切换：

```swift
//
//  AppDelegate.swift
//  GostX
//

import SwiftUI
import Cocoa
import os
import Libgost

let logger = Logger(subsystem: Bundle.main.bundleIdentifier!, category: "runtime")

@objc protocol EditMenuActions {
    func redo(_ sender: AnyObject)
    func undo(_ sender: AnyObject)
}

class AppDelegate: NSObject, NSApplicationDelegate {
    private var menu: MacExtrasConfigurator?
    private var vpnMode: Bool {
        UserDefaults.standard.bool(forKey: defaultsVpnModeKey)
    }

    func mainMenu() {
        let mainMenu = NSMenu(title: "MainMenu")
        var menuItem = mainMenu.addItem(withTitle: "", action: nil, keyEquivalent: "")
        var submenu = NSMenu(title: "Application")
        mainMenu.setSubmenu(submenu, for: menuItem)

        menuItem = mainMenu.addItem(withTitle:"Edit", action:nil, keyEquivalent:"")
        submenu = NSMenu(title:NSLocalizedString("Edit", comment:"Edit menu"))
        submenu.addItem(withTitle: "Undo", action: #selector(EditMenuActions.undo(_:)), keyEquivalent: "z")
        submenu.addItem(withTitle: "Redo", action: #selector(EditMenuActions.redo(_:)), keyEquivalent: "Z")
        submenu.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        submenu.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        submenu.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        submenu.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        mainMenu.setSubmenu(submenu, for: menuItem)

        NSApp.mainMenu = mainMenu
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        self.mainMenu()
        self.menu = MacExtrasConfigurator(delegate: self)

        Task {
            await VpnManager.shared.setup()
        }

        // Auto-start based on saved state
        if UserDefaults.standard.bool(forKey: "last_vpn_running") {
            self.start()
        }
    }

    func applicationWillTerminate(_ notification: Notification) {
        self.stop()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        NSApp.setActivationPolicy(.accessory)
        return false
    }

    func applicationSupportsSecureRestorableState(_ app: NSApplication) -> Bool {
        return true
    }

    func quit() {
        NSApp.terminate(self)
    }

    func stop() {
        if vpnMode {
            VpnManager.shared.stop()
        } else {
            LibgostStopGost()
        }
        self.menu?.updateListen(nil)
        self.menu?.toOffState()
    }

    func start() {
        if vpnMode {
            startVpnMode()
        } else {
            startProxyMode()
        }
    }

    // MARK: - VPN Mode

    private func startVpnMode() {
        // 同步 YAML 到 App Group
        let yaml = UserDefaults.standard.string(forKey: defaultsYamlKey) ?? defaultGostYAML
        AppGroupConfig.writeYaml(yaml)

        Task {
            do {
                try await VpnManager.shared.start()
                self.menu?.toOnState()
            } catch {
                logger.error("VPN start failed: \(error.localizedDescription)")
                self.menu?.toOffState()
            }
        }
    }

    // MARK: - Proxy Mode (non-VPN)

    private func startProxyMode() {
        let yaml = UserDefaults.standard.string(forKey: defaultsYamlKey) ?? defaultGostYAML

        do {
            try LibgostStartGost(yaml, "")
        } catch {
            logger.error("gost start failed: \(error.localizedDescription)")
            self.menu?.toOffState()
            return
        }

        let statusJSON = LibgostGetStatus()
        self.menu?.updateListen(statusJSON)
        self.menu?.toOnState()
    }
}
```

- [ ] **Step 4: 更新 `macos/GostX/MacExtrasConfigurator.swift` — 菜单增加 VPN 模式切换**

在 `createMenu()` 方法中，`statusListenItem` 之后、分隔线之前插入 VPN 模式切换项：

```swift
// 在 statusListenItem 添加之后、第一个 separator 之前追加：
let vpnModeItem = NSMenuItem()
vpnModeItem.title = NSLocalizedString("VPN Mode", comment: "")
vpnModeItem.state = UserDefaults.standard.bool(forKey: defaultsVpnModeKey) ? .on : .off
vpnModeItem.target = self
vpnModeItem.action = #selector(onToggleVpnMode(_:))
menu.addItem(vpnModeItem)
self.vpnModeItem = vpnModeItem
```

需要在 `MacExtrasConfigurator` 类中添加属性：

```swift
private var vpnModeItem: NSMenuItem!
```

并添加 action 方法：

```swift
@objc private func onToggleVpnMode(_ sender: NSMenuItem) {
    let newValue = sender.state != .on
    sender.state = newValue ? .on : .off
    UserDefaults.standard.set(newValue, forKey: defaultsVpnModeKey)
}
```

- [ ] **Step 5: 更新 `macos/GostX/SettingsView.swift` — YAML 保存时同步到 App Group**

在 `YamlConfigView` 的 body 中，给 `yamlConfig` 绑定添加 `.onChange`：

完整替换 `YamlConfigView`：

```swift
struct YamlConfigView: View {
    @AppStorage(defaultsYamlKey)
    private var yamlConfig = defaultGostYAML

    private let rules: [HighlightRule] = [
        HighlightRule(
            pattern: yamlCommentRule,
            formattingRule: TextFormattingRule(key: .foregroundColor, value: NSColor.systemGray)
        ),
        HighlightRule(
            pattern: yamlKeyRule,
            formattingRules: [
                TextFormattingRule(fontTraits: .bold),
                TextFormattingRule(key: .foregroundColor, value: NSColor.systemBlue),
            ]
        ),
    ]

    var body: some View {
        VStack {
            HighlightedTextEditor(text: $yamlConfig, highlightRules: rules)
                .introspect { editor in
                    editor.textView.allowsUndo = true
                    editor.textView.breakUndoCoalescing()
                    editor.textView.font = NSFont.monospacedSystemFont(ofSize: 12, weight: .regular)
                }
                .onChange(of: yamlConfig) { newValue in
                    // Sync to App Group for VPN Extension
                    AppGroupConfig.writeYaml(newValue)
                }
            Text("gost v3 YAML configuration — https://gost.run/docs/")
                .padding(.horizontal, 5)
                .font(Font.system(size: 12))
                .foregroundColor(.gray)
        }
    }
}
```

- [ ] **Step 6: 验证编译**

Xcode → GostX scheme → Cmd+B

Expected: 编译成功，无错误

- [ ] **Step 7: Commit**

```bash
cd /Users/kbliu/Workspace/project/GostX
git add macos/GostX/AppDelegate.swift macos/GostX/MacExtrasConfigurator.swift macos/GostX/SettingsView.swift macos/GostX/GostX.entitlements macos/GostX/Arguments.swift
git commit -m "feat: integrate VPN mode into main app with menu toggle and App Group sync"
```

---

### Task 8: 构建验证 & Xcode 收尾

- [ ] **Step 1: 确保 Xcode target 完整配置**

在 Xcode 中确认以下设置：

**GostXTunnel target:**
- General → Frameworks and Libraries → `Libgost.xcframework` (Embed & Sign)
- Signing & Capabilities → App Groups → `group.cn.liukebin.gostx`
- Signing & Capabilities → Network Extensions → Packet Tunnel (应自动添加)
- Build Settings → Other Linker Flags → 包含 `-framework Libgost`

**GostX (主 App) target:**
- Signing & Capabilities → App Groups → `group.cn.liukebin.gostx`

- [ ] **Step 2: 完整构建**

Xcode → Product → Build (Cmd+B) 依次选择两个 scheme。

Expected: 两个 target 均编译通过

- [ ] **Step 3: 运行验证（可选，需要 Developer ID 签名）**

1. Xcode → GostX scheme → Run (Cmd+R)
2. 在状态栏菜单中启用 **VPN Mode**
3. 点击 Start → 系统弹出 VPN 配置授权 → 允许
4. 验证 VPN 连接状态

Expected: VPN 连接成功，系统流量经 utun 转发

- [ ] **Step 4: Commit**

```bash
cd /Users/kbliu/Workspace/project/GostX
git add macos/GostX.xcodeproj/
git commit -m "chore: finalize Xcode project settings for macOS VPN build"
```
