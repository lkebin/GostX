# iOS 客户端支持设计

## 1. 背景

GostX 目前有两个客户端：Android（`android/`，Kotlin + Compose）和 macOS（`macos/`，SwiftUI + Network Extension 扩展）。两者共享同一份 Go 内核 `libgost`，分别通过 `gomobile bind` 产出 `.aar` 和 `.xcframework`。

现在需要新增 iOS 客户端：

- **UI 对齐 Android**：Home（连接按钮 + profile 列表）、配置编辑、日志、设置、文件管理五个页面，导航骨架照抄 Android。
- **功能对齐 macOS**：多 profile（新增/重命名/删除/切换）、YAML 编辑、文件管理（导入/编辑/导出/重命名/删除）、日志（跟随/历史/复制/清空）、日志开关与级别、VPN 隧道。
- **分发方式**：TestFlight（后续可能上架 App Store）。

iOS 与 macOS 均为 Swift + Xcode + 同一份 Go 库，属于 sing-box-for-apple、wireguard-apple 那类"单工程多 target"的场景，因此把 Apple 端代码集中到 `apple/` 目录共享。

## 2. 已确认的决策

| 项 | 决定 |
|---|---|
| 目录 | 新增顶层 `apple/`，把现有 `macos/` 代码迁入 |
| Xcode 工程 | 一个 `apple/GostX.xcodeproj`，4 个 target |
| target / scheme 命名 | `GostX-macOS` / `GostX-iOS` / `GostXTunnel-macOS` / `GostXTunnel-iOS` |
| 代码复用 | 平台无关代码放 `apple/Shared/`，以 synchronized folder 挂到 4 个 target；不引入 SPM |
| bundle id | iOS 与 macOS **共用** `cn.liukebin.gostx`；两端扩展共用 `cn.liukebin.gostx.tunnel` |
| App Group | 统一 `group.cn.liukebin.gostx` |
| 最低版本 | iOS 17.0；macOS 14.0（不变） |
| 运行模式 | iOS 只支持 VPN 模式（配置必须含 `tungo`），不实现进程内代理模式 |
| 页面能力差异 | iOS 不做按应用代理、快捷磁贴、开机自启、电池优化提示 |
| 日志上限 | 改为 App Group 可配（默认 2048 KB，macOS 行为不变），iOS 设置页可调 |
| 默认配置 | iOS 首次启动用最小 `tungo` 模板；macOS 保持现有 socks5 模板 |

## 3. 目标

1. 现有 macOS 客户端行为不变，仅目录与工程结构迁移。
2. 同一份 `libgost` 产物同时供 macOS 与 iOS 使用。
3. iOS 客户端具备第 1 节列出的全部功能，UI 布局与 Android 一致。
4. Network Extension 实现只维护一份，两个扩展 target 通过 entitlements / Info.plist / 平台设置区分。
5. `make macos`、`make ios`、`make ios-release` 一条命令各自产出对应平台产物。

## 4. 非目标

- 不实现 iOS 的按应用代理（App Filter）：App Store 分发的 app 无法使用 `NEAppRule`（Per-App VPN 属 MDM 能力）。
- 不实现 iOS 的进程内代理模式（macOS 在配置无 `tungo` 时启动本地 gost 服务）。
- 不实现快捷磁贴、开机自启、后台常驻、通知栏停止按钮等 Android 专有能力。
- 不实现 on-demand VPN 规则、iOS 小组件（Widget）、快捷指令（App Intents）。
- 不引入 SwiftUI Observation 框架，共享 ViewModel 继续使用 `ObservableObject`。
- 不做 TestFlight 的自动上传，`make ios-release` 只产出 `.ipa`。
- 不改动 Android 端任何代码与构建产物语义。

## 5. 目录结构

```
apple/
  GostX.xcodeproj/
  Shared/                                # 平台无关代码，按 target 需要分组
    Common/                              # app 与扩程都需要
      AppGroupConfig.swift
    App/                                 # 仅两个 app target 需要
      Arguments.swift                    # defaultGostYAML、UserDefaults key
      ConfigRepository.swift
      AppLogger.swift
      FileRepository.swift
      FileManageViewModel.swift
      LogViewModel.swift
      VpnManager.swift
      Clipboard.swift                    # 新增：跨平台剪贴板
      GostDefaults.swift                 # 新增：按平台选择默认 YAML
      ProfileNameValidator.swift         # 新增：profile 名校验
    Tunnel/                              # 仅两个扩展 target 需要
      PacketTunnelProvider.swift
  macOS/                                 # macOS 壳与专属 UI
    main.swift
    AppDelegate.swift
    MacExtrasConfigurator.swift
    SettingsView.swift                   # NavigationSplitView 三栏
    ProfileListView.swift
    FileListView.swift
    FileContentView.swift
    YamlEditorView.swift
    LogContentView.swift
    LogOptionsView.swift
    GostX.icon/
    Assets.xcassets/
    en.lproj/  zh-Hans.lproj/
  iOS/                                   # iOS 壳与专属 UI
    GostXApp.swift
    HomeView.swift
    ConfigEditView.swift
    LogsView.swift
    SettingsView.swift
    FileManageView.swift
    FileEditorView.swift
    Assets.xcassets/
    Localizable.xcstrings
  Tunnel/
    macOS/Info.plist
    macOS/GostXTunnel.entitlements
    iOS/Info.plist
    iOS/GostXTunnel.entitlements
  Tests/
    GostXTests.swift                     # 现有用例，两个测试 target 共用
    SharedConfigTests.swift              # 新增：本次改动相关的用例
  Frameworks/Libgost.xcframework
  Configuration/
    DevelopmentTeam.xcconfig
    DevelopmentTeam.example.xcconfig
    ExportOptions.plist                  # macOS DMG 公证
    ExportOptions-appstore.plist         # iOS archive 导出
```

迁移使用 `git mv`，保留文件历史；迁移完成后**删除空的 `macos/` 目录**。

### 5.1 target 配置要点

- 两个 app target 显式设置 `PRODUCT_MODULE_NAME = GostX`；两个扩展 target 设置 `PRODUCT_MODULE_NAME = GostXTunnel`。
  原因：target 名含连字符时默认模块名会变成 `GostX_macOS`，破坏 `@testable import GostX` 与扩展 Info.plist 里的 `$(PRODUCT_MODULE_NAME).PacketTunnelProvider`。
- `apple/Shared/Common/`、`apple/Shared/App/`、`apple/Shared/Tunnel/`、`apple/iOS/`、`apple/Tests/` 以 synchronized folder 形式加入 target membership，后续新增文件无需修改 `project.pbxproj`。各 target 的成员关系：
  - `GostX-macOS` / `GostX-iOS` → `Shared/Common` + `Shared/App` + 各自的平台目录
  - `GostXTunnel-macOS` / `GostXTunnel-iOS` → `Shared/Common` + `Shared/Tunnel`
  - 两个测试 target → `Tests`
  拆分 `Common` / `App` / `Tunnel` 的原因：扩展不能编译 `Shared/App`（含 AppKit/UIKit UI 依赖），而 `AppGroupConfig` 两端都要用。
- scheme：`GostX-macOS`、`GostX-iOS`，各自包含对应扩展 target；测试 target 挂到对应 scheme 的 Test 动作。

## 6. 构建链与产物

### 6.1 Go 产物：一份多平台 xcframework

`libgost/Makefile` 把 `macos-xcframework` 换成 `apple-xcframework`：

```make
apple-xcframework:
	CGO_ENABLED=1 GOFLAGS="-buildvcs=false" $(GOMOBILE) bind \
	  -tags with_gvisor \
	  -target macos/arm64,ios/arm64,iossimulator/arm64 \
	  -trimpath -ldflags="-s -w" \
	  -o Libgost.xcframework \
	  .
```

- `with_gvisor` 必须保留：darwin 平台 `tunStackType = "gvisor"`（见 `libgost/tun_stack_darwin.go`）。
- iOS 复用 `libgost/tun_darwin.go`：`GOOS=ios` 满足 `//go:build darwin` 条件，`GetTunnelFileDescriptor` 无需新增平台文件。
- 单一 xcframework 含 `macos-arm64`、`ios-arm64`、`iossimulator-arm64` 三个切片。Xcode 嵌入 XCFramework 时按平台裁剪切片，模拟器切片不会进入 App Store 归档。

### 6.2 根 Makefile

```make
apple-lib:      # 只出 xcframework，复制到 apple/Frameworks/
macos:          # apple-lib + xcodebuild -scheme GostX-macOS
macos-release:  # 现有 DMG + 公证流程，路径改到 apple/
ios:            # apple-lib + xcodebuild -scheme GostX-iOS -destination 'generic/platform=iOS' -allowProvisioningUpdates
ios-sim:        # apple-lib + 模拟器构建（免签名，日常开发用）
ios-release:    # apple-lib + archive + exportArchive → .ipa
clean:          # 增加清理 apple/Frameworks 与 apple/build
```

`macos-release` 的产品路径从 `macos/build` 改为 `apple/build`。

## 7. iOS 应用结构

### 7.1 导航

```swift
NavigationStack(path: $path) { HomeView() }
    .navigationDestination(for: Route.self) { … }

enum Route { case configEdit(profileId: String), logs, settings, fileManage }
```

与 Android 一致：Home 为根，顶栏图标进入日志/设置，profile 行进入配置编辑，设置内进入文件管理。不使用 TabBar。

### 7.2 页面映射

| 页面 | Android 元素 | iOS 实现 |
|---|---|---|
| Home | 顶栏「GostX」+ 日志/新建/设置三个图标；profile 卡片列表（单选圆点 + chevron）；空态文案；屏幕底部中央 72dp 圆形连接按钮（连接=实心、过渡=转圈、错误=红色）；新建 profile 弹窗（重名与非法字符校验） | `List` + 行内圆点（`largecircle.fill.circle`/`circle`）+ chevron；`.safeAreaInset(edge: .bottom)` 放 72pt 圆形按钮，颜色与转圈规则照搬；工具栏三个 SF Symbol；新建用带输入框的 `.alert`；错误用 `.alert` |
| ConfigEdit | 返回 + 保存/重命名/删除；YAML 编辑区；校验错误提示；未保存拦截 | `TextEditor` 等宽字体；工具栏 `Menu`（保存/重命名/删除）；删除用 `confirmationDialog`；离开拦截同款弹窗 |
| Logs | 跟随/复制/清空三个按钮；日志行列表；空态 | 复用 `Shared/LogViewModel`；工具栏三个按钮；跟随时自动滚到底部 |
| Settings | 日志开关 + 重启提示、日志级别、日志最大体积、文件管理入口、版本号 | `List` 两段（Logging / Files）+ 底部版本号；级别与体积用 `confirmationDialog` 单选 |
| FileManage | 列表 + 导入按钮；每项菜单：重命名/导出/删除；重名覆盖确认；删除确认 | `List` + 工具栏导入（`.fileImporter`）；行内 `Menu`；导出用 `ShareLink`；覆盖与删除用 `confirmationDialog` |

### 7.3 状态与数据流

- 不新增 ViewModel 层：`ConfigRepository.shared`、`VpnManager.shared`、`LogViewModel`、`FileManageViewModel` 直接作为 `ObservableObject` 被 iOS 视图订阅。
- `HomeView` 自己持有 `Route` 路径与弹窗状态。
- VPN 状态一律以 `VpnManager.shared.status`（`NEVPNConnection.status`）为准，不做本地乐观状态。
- 连接前必须把当前生效配置写入 App Group（`AppGroupConfig.writeYaml(ConfigRepository.shared.activeConfig)`）。macOS 是在 `AppDelegate.startVpnMode()` 里同步，iOS 由 `VpnManager` 在 `start()` 前完成，扩展只读 App Group。

### 7.4 多语言

- iOS 使用 `apple/iOS/Localizable.xcstrings`（String Catalog，英文为源语言 + 简体中文）。
- 文案取自 Android 的 `values/strings.xml` 与 `values-zh/strings.xml`，因为 iOS UI 与 Android 一一对应。
- macOS 现有 `en.lproj` / `zh-Hans.lproj` 保持不动。

## 8. Network Extension 与生命周期

### 8.1 一份实现

`apple/Shared/Tunnel/PacketTunnelProvider.swift` 由 `GostXTunnel-macOS` 与 `GostXTunnel-iOS` 共同编译，流程沿用现有 macOS 实现：

```text
启动：
  读取 App Group 日志设置 → SetWorkDir / SetLogFile / SetLogMaxSize / SetLogLevel / SetTimezone
  → 读取 App Group 的 gost.yaml
  → LibgostStartGost(yaml, "")
  → setTunnelNetworkSettings(
        10.0.0.2 + 10.0.0.3 / MTU 1500 / DNS 10.0.0.3 / LibgostVPNRoutes 附加路由)
  → LibgostGetTunnelFileDescriptor()
  → LibgostStartTun(fd, 1500)
停止：
  LibgostStopTun() + LibgostStopGost()
```

平台差异只通过 Info.plist / entitlements / build settings 表达，不引入新的 `#if os()` 分支。

iOS 的扩展 Info.plist 只需要 `NSExtension` 段（`com.apple.networkextension.packet-tunnel` + `$(PRODUCT_MODULE_NAME).PacketTunnelProvider`），不声明额外的 `UIBackgroundModes`；App 侧也不需要新增隐私用途描述键（DNS 在 TUN 层接管，不触发本地网络权限）。

### 8.2 标识复用

两端扩展 bundle id 相同（`cn.liukebin.gostx.tunnel`），因此 `VpnManager.tunnelBundleId` 的硬编码值保持不变，不引入 `GostXTunnelBundleID` 配置键。

### 8.3 内存上限

iOS Network Extension 的常驻内存上限约 50 MB，Android 使用的 100 MB 软上限不适用。Go 侧新增可配置入口：

```go
// SetMemoryLimitBytes 以字节为单位设置 Go 运行时软内存上限。
// bytes <= 0 表示不限制。
func SetMemoryLimitBytes(bytes int64)

// SetMemoryLimit 保留原语义，内部调用 SetMemoryLimitBytes。
func SetMemoryLimit(enabled bool)
```

iOS 扩展启动时调用 `LibgostSetMemoryLimitBytes(30 << 20)` 并保持 `GOGC=50`；日志默认上限 2 MB、默认级别 `info`。macOS 扩展维持现状（不调用该函数）。

### 8.4 错误回传

`NEVPNConnection` 不携带错误信息。扩展在 `cancelTunnelWithError` 之前把消息写入 App Group 容器下的 `tunnel_error.txt`；App 观察到状态变为 `.disconnected` 时读取并删除该文件，用 alert 展示。macOS 侧不改动。

### 8.5 配置校验

iOS 无进程内代理模式，因此启动与保存时要求：

1. `LibgostValidateConfig(yaml)` 返回空字符串；
2. YAML 中包含 `tungo` 服务（沿用 macOS 现有的字符串判定方式，不新增 Go API）。

任一条件不满足则拒绝连接，并提示「该配置不支持 iOS」。

### 8.6 其他生命周期决策

- **不自动启动**：macOS 的 `last_vpn_running` 自恢复不适用于 iOS，App 启动只读取当前真实状态。
- **不实现 `sleep`/`wake`**：与 macOS 现状一致，交给系统。
- **不实现 on-demand 规则**：v1 不做。

## 9. 共享代码边界

### 9.1 迁入 `apple/Shared/`（内容原样，逻辑不变）

- `Shared/Common/`：`AppGroupConfig.swift`
- `Shared/App/`：`Arguments.swift`、`ConfigRepository.swift`、`AppLogger.swift`、`FileRepository.swift`、`FileManageViewModel.swift`、`LogViewModel.swift`、`VpnManager.swift`
- `Shared/Tunnel/`：`PacketTunnelProvider.swift`

### 9.2 需要小幅修改的共享文件

| 文件 | 改动 |
|---|---|
| `FileManageViewModel.swift`、`LogViewModel.swift` | 剪贴板调用改为 `Shared/Clipboard.swift`（`NSPasteboard` / `UIPasteboard`），去掉 `import AppKit` |
| `AppGroupConfig.swift` | 新增 `logMaxSizeKB`（默认 2048） |
| `AppLogger.swift`、`Tunnel/PacketTunnelProvider.swift` | 日志上限从写死 2 MB 改为读 `logMaxSizeKB` |
| `ConfigRepository.swift` | 默认配置改用 `GostDefaults.yaml`（按平台选择模板） |

### 9.3 平台专属

- `apple/macOS/`：`AppDelegate`、`main.swift`、`MacExtrasConfigurator`、`SettingsView`、`ProfileListView`、`FileListView`、`FileContentView`、`YamlEditorView`、`LogContentView`、`LogOptionsView`、图标、lproj、entitlements、Info.plist。
- `apple/iOS/`：第 5 节列出的 iOS 视图与资源。

### 9.4 不改动

- `libgost` 除 `SetMemoryLimitBytes` 外不动；`darwin` 相关实现、`vpnChainName`/`vpnDNSServiceAddr` 逻辑、`fakeip` 持久化均保持现状。
- macOS 的菜单栏、自动启动、代理模式、DMG 打包逻辑不动。
- App Group 文件名（`gost.yaml`、`gost.log`）、日志格式不动。

## 10. 标识与签名

| target | bundle id | entitlements |
|---|---|---|
| `GostX-macOS` | `cn.liukebin.gostx` | app-sandbox、application-groups、network.client/server、files.user-selected、networkextension[packet-tunnel-provider]（不变） |
| `GostXTunnel-macOS` | `cn.liukebin.gostx.tunnel` | app-sandbox、application-groups、network.client/server、networkextension[packet-tunnel-provider]（不变） |
| `GostX-iOS` | `cn.liukebin.gostx` | application-groups、networkextension[packet-tunnel-provider] |
| `GostXTunnel-iOS` | `cn.liukebin.gostx.tunnel` | application-groups、networkextension[packet-tunnel-provider] |

iOS 的 entitlements 不包含 macOS 专属键（`com.apple.security.app-sandbox`、`network.client`、`network.server`、`files.user-selected.read-write`）。

后台配置（人工步骤，需在实施时完成）：

1. App ID `cn.liukebin.gostx`：启用 iOS 平台、Network Extensions、App Groups；
2. App ID `cn.liukebin.gostx.tunnel`：启用 iOS 平台、Network Extensions、App Groups；
3. App Store Connect 新建 app 记录（bundle id `cn.liukebin.gostx`，平台先选 iOS）；
4. macOS 继续使用 Developer ID 分发；若将来上架 Mac App Store，在同一 app 记录里添加 macOS 平台（universal purchase）。

## 11. 测试与验收

### 11.1 自动化

| 层 | 内容 |
|---|---|
| Go | `cd libgost && go test .`；新增 `TestSetMemoryLimitBytes`（正值生效、0 与负值表示不限制） |
| Swift 共享 | 现有 `LogViewModelTests` 随文件迁移；新增 `SharedConfigTests`：`GostDefaults.yaml` 在 iOS 下含 `tungo`、`AppGroupConfig.logMaxSizeKB` 默认 2048、`FileRepository` 文件名校验 |
| target | `apple/Tests/GostXTests.swift` 与 `SharedConfigTests.swift` 同时加入 macOS 与 iOS 测试 target（iOS 为 hosted 测试，TEST_HOST 指向 iOS app） |

模拟器无法运行 Network Extension，扩展行为不进单元测试。iOS hosted 测试会启动 App，因此 `GostXApp` 启动时若检测到 `XCTestConfigurationFilePath` 环境变量则跳过 `VpnManager.setup()`，避免测试依赖 Network Extension 权限。

### 11.2 iOS 真机验收清单

1. 首次连接弹出「允许 VPN 配置」，连接成功后按钮呈连接态；
2. 流量走通：Safari 访问外网，DNS 由 `10.0.0.3` 接管；
3. profile 中 `handler.metadata.routes` 指定的内网地址能进入隧道；
4. 断开后重连成功；切换 Wi-Fi/蜂窝后隧道仍可用；
5. 故意写错 YAML 或使用无 `tungo` 配置：连接被拒，App 展示 `tunnel_error.txt` 中的原因；
6. 日志页 tail/跟随/复制/清空正常；设置中修改级别与上限后重启生效；
7. 文件管理：导入、编辑、导出（系统分享）、重命名、删除正常；
8. 内存：Xcode 调试挂到扩展进程，连续 10 分钟大流量下 RSS 峰值 < 50 MB。

### 11.3 macOS 回归

`make macos` 产出可运行 app，并人工验证：起停 VPN、编辑并切换 profile、导入文件、日志显示与日志级别切换。四项通过视为共享边界切分正确。

### 11.4 Android 回归

无代码改动；`libgost` 仅新增导出函数，`.aar` 产物与行为不变。

## 12. 风险与兜底

| 风险 | 说明 | 应对 |
|---|---|---|
| utun fd 扫描在 iOS 不可用 | `LibgostGetTunnelFileDescriptor()` 依赖 fd 扫描（wireguard-apple 同款技巧），只能在真机确认 | 若拿不到 fd，改为在 `libgost` 新增 `StartTunPacketFlow(read, write)`，由 gVisor 直接消费 `packetFlow` 的包。此改动会升级为架构级变更，**先暂停并与作者确认** |
| 扩展内存超限 | 50 MB 上限对 gost + gvisor 偏紧 | `SetMemoryLimitBytes(30MB)`、日志上限、必要时裁剪注册的 connector/dialer；验收项 8 为硬性 gate |
| App Store 审核 | NE + 代理类 app 审核较严 | v1 先 TestFlight 内部测试，上架前再评估 |
| xcframework 切片 | 模拟器切片进入归档可能被拒 | 已验证 Xcode 按平台裁剪；`ios-release` 使用默认归档流程，不额外打包模拟器切片 |
| 工程合并风险 | 4 个 target 的 pbxproj 改动面大 | 结构迁移单独提交，`make macos` 与 archive 通过后才继续 |

## 13. 实施顺序

1. **结构迁移**：`git mv` 到 `apple/`，建 4 个 target 与 scheme，`Shared/` 接入 target membership，改根 Makefile 路径，同步更新 `.gitignore`（`apple/Configuration/*.xcconfig`、`apple/Frameworks/`、`apple/build/`）、`AGENTS.md` 与 `README.md` 中的目录说明。验收：`make macos` 可运行 + macOS 四项回归。
2. **libgost**：新增 `SetMemoryLimitBytes` 及测试。验收：`go test .` 通过。
3. **共享层小幅改动**：`Clipboard`、`GostDefaults`、`logMaxSizeKB`、日志上限接入。验收：macOS 回归仍通过，Swift 共享测试通过。
4. **iOS UI**：五个页面 + `Localizable.xcstrings` + Assets。验收：`make ios-sim` 编译通过，模拟器走查界面与导航。
5. **iOS 扩展接线与真机验收**：内存上限、错误回传、tungo 校验、`make ios` / `make ios-release`。验收：11.2 全部条目通过。

每步单独提交，前一步验收不通过不进入下一步。