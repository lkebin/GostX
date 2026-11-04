# iOS 客户端支持实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `apple/` 目录下用单个 Xcode 工程同时构建 macOS 与 iOS 客户端，iOS 端 UI 对齐 Android、功能对齐 macOS，共享同一份 `libgost` 产物。

**Architecture:** 四个 target（`GostX-macOS`、`GostX-iOS`、`GostXTunnel-macOS`、`GostXTunnel-iOS`）共用一个 `project.pbxproj`；平台无关 Swift 代码放在 `apple/Shared/{Common,App,Tunnel}` 并通过 Xcode synchronized folder 挂到对应 target；`gomobile bind` 产出含 `macos/arm64`、`ios/arm64`、`iossimulator/arm64` 三个切片的 `Libgost.xcframework`；Network Extension 实现只有一份，由两个扩展 target 分别编译。

**Tech Stack:** Swift 5 / SwiftUI / NetworkExtension（`NEPacketTunnelProvider`）、Go 1.27 + `gomobile bind`、Xcode 27（iOS 27 SDK，部署目标 iOS 17.0 / macOS 14.0）、GNU Make。

**Spec:** `docs/superpowers/specs/2026-11-04-ios-support-design.md`

## Global Constraints

- iOS 最低部署目标 `17.0`；macOS 最低部署目标保持 `14.0`（现有工程里为 13.0，不因本计划下调或上调）。
- bundle id：app `cn.liukebin.gostx`；扩展 `cn.liukebin.gostx.tunnel`。iOS 与 macOS 两边**取值相同**。
- App Group：`group.cn.liukebin.gostx`，四个 target 统一。
- 两个 app target 必须显式设置 `PRODUCT_MODULE_NAME = GostX`；两个扩展 target 必须显式设置 `PRODUCT_MODULE_NAME = GostXTunnel`。
- iOS entitlements 不得包含 macOS 专属键：`com.apple.security.app-sandbox`、`com.apple.security.network.client/server`、`com.apple.security.files.user-selected.read-write`。
- `gomobile bind` 必须带 `-tags with_gvisor`；目标固定为 `macos/arm64,ios/arm64,iossimulator/arm64`。
- iOS 只支持 VPN 模式：配置必须包含 `tungo` 服务，不实现进程内代理模式。
- iOS 不实现：按应用代理、快捷磁贴、开机自启、电池优化提示、on-demand 规则、`sleep`/`wake`。
- 共享代码不得引入 Observation 框架，继续使用 `ObservableObject`。
- 所有 Xcode 工程改动后用 `xcodebuild -list -project apple/GostX.xcodeproj` 校验工程可解析。
- 提交信息使用 Conventional Commits（`build:`、`feat:`、`fix:`、`docs:`、`test:`）。

## Review Focus

- **扩展内存**：iOS Network Extension 常驻上限约 50 MB，`libgost` 在 Android 上用 100 MB 软上限；若 iOS 扩展沿用默认值，大流量下会被系统强杀。由 Task 2 + Task 11 覆盖（`SetMemoryLimitBytes(30MB)`）。
- **fd 扫描**：`LibgostGetTunnelFileDescriptor()` 在 iOS 扩展进程内可能拿不到 utun fd，表现为连接启动后立刻失败。Task 11 的验收步骤必须在真机确认；失败时停止实施并上报，不得自行改造 `libgost` 数据面。
- **配置缺失 tungo**：用户从 macOS/Android 带入的 socks5 配置在 iOS 上无法工作，必须给出明确提示而不是静默失败。Task 3（校验器）+ Task 6（编辑页提示）+ Task 11（连接前拒绝）覆盖。
- **App Group 写入时机**：连接前若未把生效 YAML 写入 App Group，扩展会读到旧配置（表现为改了配置但不生效）。Task 11 在 `VpnManager.start()` 内覆盖。
- **模拟器切片**：`Libgost.xcframework` 含 `iossimulator` 切片，真机归档必须只带 arm64 切片。Task 11 用 `xcodebuild archive` + 检查 `.app` 内 framework 架构覆盖。

---

### Task 1: 迁移 macOS 客户端到 `apple/` 并改用同步文件夹

**Files:**
- Move: `macos/GostX/{AppDelegate,Arguments,AppLogger,ConfigRepository,FileContentView,FileListView,FileManageViewModel,FileRepository,LogContentView,LogOptionsView,LogViewModel,MacExtrasConfigurator,ProfileListView,SettingsView,VpnManager,YamlEditorView}.swift`
- Move: `macos/GostX/GostXTunnel/PacketTunnelProvider.swift` → `apple/Shared/Tunnel/PacketTunnelProvider.swift`
- Move: `macos/GostX/AppGroupConfig.swift` → `apple/Shared/Common/AppGroupConfig.swift`
- Move: 其余 `macos/GostX/` 资源（`main.swift`、`Assets.xcassets`、`Preview Content`、`GostX.entitlements`、`Info.plist`、`GostX.icon`、`en.lproj`、`zh-Hans.lproj`）
- Move: `macos/GostXTunnel/{Info.plist,GostXTunnel.entitlements}` → `apple/Tunnel/macOS/`
- Move: `macos/{Frameworks,Configuration,GostXTests,GostXUITests}`、`macos/GostX.xcodeproj` → `apple/`
- Modify: `apple/GostX.xcodeproj/project.pbxproj`
- Modify: `Makefile`、`libgost/Makefile`、`.gitignore`、`AGENTS.md`、`README.md`

**Interfaces:**
- Consumes: 无
- Produces: 目录 `apple/{Shared/Common,Shared/App,Shared/Tunnel,macOS,iOS,Tunnel/macOS,Tunnel/iOS,Tests,Frameworks,Configuration}`；工程内四个 target 的最终命名空间；`make macos` 构建入口。

- [ ] **Step 1: 建立目录并移动文件**

```bash
cd /Users/kbliu/Workspace/project/GostX
mkdir -p apple/Shared/{Common,App,Tunnel} apple/macOS apple/iOS apple/Tunnel/{macOS,iOS} apple/Tests

git mv macos/GostX.xcodeproj apple/GostX.xcodeproj
git mv macos/Frameworks apple/Frameworks
git mv macos/Configuration apple/Configuration
git mv macos/GostXTests apple/Tests/GostXTests
git mv macos/GostXUITests apple/Tests/GostXUITests
git mv macos/GostX.icon apple/macOS/GostX.icon
git mv macos/GostX/en.lproj apple/macOS/en.lproj
git mv macos/GostX/zh-Hans.lproj apple/macOS/zh-Hans.lproj
git mv macos/GostX/Assets.xcassets apple/macOS/Assets.xcassets
git mv "macos/GostX/Preview Content" "apple/macOS/Preview Content"
git mv macos/GostX/Info.plist apple/macOS/Info.plist
git mv macos/GostX/GostX.entitlements apple/macOS/GostX.entitlements
git mv macos/GostX/main.swift apple/macOS/main.swift
git mv macos/GostXTunnel/Info.plist apple/Tunnel/macOS/Info.plist
git mv macos/GostXTunnel/GostXTunnel.entitlements apple/Tunnel/macOS/GostXTunnel.entitlements

git mv macos/GostX/AppGroupConfig.swift apple/Shared/Common/AppGroupConfig.swift
git mv macos/GostXTunnel/PacketTunnelProvider.swift apple/Shared/Tunnel/PacketTunnelProvider.swift
for f in AppDelegate Arguments AppLogger ConfigRepository FileContentView FileListView \
         FileManageViewModel FileRepository LogContentView LogOptionsView LogViewModel \
         MacExtrasConfigurator ProfileListView SettingsView VpnManager YamlEditorView; do
  git mv "macos/GostX/$f.swift" "apple/Shared/App/$f.swift"
done
```

`apple/Shared/App/` 里除 `AppDelegate.swift` 以外的文件最终都属于共享层；`AppDelegate.swift` 稍后要移回 `apple/macOS/`，见 Step 2。

- [ ] **Step 2: 把 macOS 专属文件放回 `apple/macOS/`**

```bash
git mv apple/Shared/App/AppDelegate.swift apple/macOS/AppDelegate.swift
git mv apple/Shared/App/MacExtrasConfigurator.swift apple/macOS/MacExtrasConfigurator.swift
git mv apple/Shared/App/SettingsView.swift apple/macOS/SettingsView.swift
git mv apple/Shared/App/ProfileListView.swift apple/macOS/ProfileListView.swift
git mv apple/Shared/App/FileListView.swift apple/macOS/FileListView.swift
git mv apple/Shared/App/FileContentView.swift apple/macOS/FileContentView.swift
git mv apple/Shared/App/YamlEditorView.swift apple/macOS/YamlEditorView.swift
git mv apple/Shared/App/LogContentView.swift apple/macOS/LogContentView.swift
git mv apple/Shared/App/LogOptionsView.swift apple/macOS/LogOptionsView.swift
rmdir macos/GostX macos/GostXTunnel macos 2>/dev/null || true
```

此时 `apple/Shared/App/` 应只剩：`Arguments.swift`、`AppLogger.swift`、`ConfigRepository.swift`、`FileManageViewModel.swift`、`FileRepository.swift`、`LogViewModel.swift`、`VpnManager.swift`。

- [ ] **Step 3: 修改工程里的 macOS 组路径与 target 名**

在 `apple/GostX.xcodeproj/project.pbxproj` 中做以下替换：

1. 组路径（唯一匹配 `path = GostX;` 且属于组 `B532A668285B528900159BAC`）：

```
			path = GostX;
			sourceTree = "<group>";
```
改为
```
			path = macOS;
			sourceTree = "<group>";
```

2. 从组 `B532A668285B528900159BAC` 的 children 列表中删除这 9 行（它们将改由同步文件夹提供）：

```
				430349593004D6B700FD9EE9 /* AppGroupConfig.swift */,
				F188A4B6A6F14F1D867F7D30 /* VpnManager.swift */,
				16E9D6522B494B409C95F0FE /* AppLogger.swift */,
				B5F6387829DEFF31009A1287 /* Arguments.swift */,
				B5B1C1A429DD6E90008AA944 /* FileRepository.swift */,
				5D840A91D1C37F07D57AD8A1 /* ConfigRepository.swift */,
				8BFCA548038B9C1B568E78C0 /* LogViewModel.swift */,
				6B40B99520AC17D0C83EEFF5 /* FileManageViewModel.swift */,
```
同时把顶层组的这一行删除（`B532A65D285B528900159BAC` 的 children）：
```
				430349593004D6B700FD9EE9 /* AppGroupConfig.swift */,
```

3. 删除这些 `PBXBuildFile` 条目（共享文件改由同步文件夹编译，`PacketTunnelProvider.swift`/`AppGroupConfig.swift` 也由扩展的同步文件夹提供）：

```
		2FA7674F8D9746F99910289A /* VpnManager.swift in Sources */ = {isa = PBXBuildFile; fileRef = F188A4B6A6F14F1D867F7D30 /* VpnManager.swift */; };
		4303495A3004D6B700FD9EE9 /* AppGroupConfig.swift in Sources */ = {isa = PBXBuildFile; fileRef = 430349593004D6B700FD9EE9 /* AppGroupConfig.swift */; };
		4303495B3004D6D500FD9EE9 /* AppGroupConfig.swift in Sources */ = {isa = PBXBuildFile; fileRef = 430349593004D6B700FD9EE9 /* AppGroupConfig.swift */; };
		0874FE9F0ADC49BBB6CD3626 /* PacketTunnelProvider.swift in Sources */ = {isa = PBXBuildFile; fileRef = 296DECB2BD4347C19DFD902E /* PacketTunnelProvider.swift */; };
		5B773BD6DC67E0481B3E9F01 /* FileManageViewModel.swift in Sources */ = {isa = PBXBuildFile; fileRef = 6B40B99520AC17D0C83EEFF5 /* FileManageViewModel.swift */; };
		B5B1C1A329DD6E90008AA943 /* FileRepository.swift in Sources */ = {isa = PBXBuildFile; fileRef = B5B1C1A429DD6E90008AA944 /* FileRepository.swift */; };
		B5F6387929DEFF31009A1287 /* Arguments.swift in Sources */ = {isa = PBXBuildFile; fileRef = B5F6387829DEFF31009A1287 /* Arguments.swift */; };
		B949D5D298F19829F1C9EBAB /* LogViewModel.swift in Sources */ = {isa = PBXBuildFile; fileRef = 8BFCA548038B9C1B568E78C0 /* LogViewModel.swift */; };
		E086218C337C33C88BB4052CA /* ConfigRepository.swift in Sources */ = {isa = PBXBuildFile; fileRef = 5D840A91D1C37F07D57AD8A1 /* ConfigRepository.swift */; };
		EBCBCEB114E34B4BB5D68150 /* AppLogger.swift in Sources */ = {isa = PBXBuildFile; fileRef = 16E9D6522B494B409C95F0FE /* AppLogger.swift */; };
```
并从 app 与扩展的 `PBXSourcesBuildPhase.files` 列表里删除对应的 `/* X in Sources */,` 行（`B532A662285B528900159BAC` app、`4303493E3004D3A000FD9EE9` 扩展）。

4. 删除这些 `PBXFileReference` 条目：

```
		430349593004D6B700FD9EE9 /* AppGroupConfig.swift */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; name = AppGroupConfig.swift; path = GostX/AppGroupConfig.swift; sourceTree = "<group>"; };
		296DECB2BD4347C19DFD902E /* PacketTunnelProvider.swift */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; name = PacketTunnelProvider.swift; path = GostXTunnel/PacketTunnelProvider.swift; sourceTree = "<group>"; };
		F188A4B6A6F14F1D867F7D30 /* VpnManager.swift */ = ...
		16E9D6522B494B409C95F0FE /* AppLogger.swift */ = ...
		B5F6387829DEFF31009A1287 /* Arguments.swift */ = ...
		B5B1C1A429DD6E90008AA944 /* FileRepository.swift */ = ...
		5D840A91D1C37F07D57AD8A1 /* ConfigRepository.swift */ = ...
		8BFCA548038B9C1B568E78C0 /* LogViewModel.swift */ = ...
		6B40B99520AC17D0C83EEFF5 /* FileManageViewModel.swift */ = ...
```
（`...` 表示保留原行其余内容，只删除整行。）

5. 删除旧的 `GostXTunnel` 同步组（`430349453004D3A000FD9EE9`）与其 `PBXFileSystemSynchronizedBuildFileExceptionSet`（`430349503004D3A000FD9EE9`），新增三个同步组：

```
/* Begin PBXFileSystemSynchronizedRootGroup section */
		A1000000000000000000000E /* Common */ = {isa = PBXFileSystemSynchronizedRootGroup; exceptions = (); explicitFileTypes = {}; explicitFolders = (); path = Shared/Common; sourceTree = "<group>"; };
		A1000000000000000000000F /* App */ = {isa = PBXFileSystemSynchronizedRootGroup; exceptions = (); explicitFileTypes = {}; explicitFolders = (); path = Shared/App; sourceTree = "<group>"; };
		B2000000000000000000000B /* Tunnel */ = {isa = PBXFileSystemSynchronizedRootGroup; exceptions = (); explicitFileTypes = {}; explicitFolders = (); path = Shared/Tunnel; sourceTree = "<group>"; };
/* End PBXFileSystemSynchronizedRootGroup section */
```
把顶层组（`B532A65D285B528900159BAC`）children 里的 `430349453004D3A000FD9EE9 /* GostXTunnel */,` 替换为：

```
				A1000000000000000000000E /* Common */,
				A1000000000000000000000F /* App */,
				B2000000000000000000000B /* Tunnel */,
```

6. 两个 target 的成员关系与命名：

```
		430349413004D3A000FD9EE9 /* GostXTunnel */ = {
			...
			fileSystemSynchronizedGroups = (
				A1000000000000000000000E /* Common */,
				B2000000000000000000000B /* Tunnel */,
			);
			name = "GostXTunnel-macOS";
			productName = GostXTunnel;
```
```
		B532A665285B528900159BAC /* GostX */ = {
			...
			fileSystemSynchronizedGroups = (
				A1000000000000000000000E /* Common */,
				A1000000000000000000000F /* App */,
			);
			name = "GostX-macOS";
			productName = GostX;
```
（这两个 target 原来没有 `fileSystemSynchronizedGroups`（app）或只含旧 GostXTunnel 组（扩展），按上面替换。）

7. 扩展 target 的 entitlements / Info.plist 路径改为新位置（Debug 与 Release 各一处）：

```
				CODE_SIGN_ENTITLEMENTS = Tunnel/macOS/GostXTunnel.entitlements;
				INFOPLIST_FILE = Tunnel/macOS/Info.plist;
```
app target 的两处改为：
```
				CODE_SIGN_ENTITLEMENTS = macOS/GostX.entitlements;
				INFOPLIST_FILE = macOS/Info.plist;
				DEVELOPMENT_ASSET_PATHS = "\"macOS/Preview Content\"";
```
并给两个 app target 增加：
```
				PRODUCT_MODULE_NAME = GostX;
```
给两个扩展 target 增加：
```
				PRODUCT_MODULE_NAME = GostXTunnel;
```

- [ ] **Step 4: 校验工程可解析**

```bash
cd /Users/kbliu/Workspace/project/GostX
xcodebuild -list -project apple/GostX.xcodeproj
```
Expected: 输出含 `GostX-macOS`、`GostXTunnel-macOS`、`GostXTests`、`GostXUITests`，且 `Schemes:` 下有 `GostX`。

- [ ] **Step 5: 重命名 scheme**

```bash
cd /Users/kbliu/Workspace/project/GostX
git mv apple/GostX.xcodeproj/xcshareddata/xcschemes/GostX.xcscheme \
       apple/GostX.xcodeproj/xcshareddata/xcschemes/GostX-macOS.xcscheme
```
在该文件中把 `BlueprintName = "GostX"` 改为 `BlueprintName = "GostX-macOS"`（两处：BuildableReference 与 TestAction 的 BuildableReference；`BuildableName = "GostX.app"` 保持不变）。

- [ ] **Step 6: 更新 Makefile**

`libgost/Makefile`：把 `macos-xcframework` 目标改名为 `apple-xcframework`，内容替换为：

```make
# Apple .xcframework via gomobile bind
# 三个切片：macOS 真机、iOS 真机、iOS 模拟器（Xcode 嵌入时按平台裁剪）
apple-xcframework:
	CGO_ENABLED=1 GOFLAGS="-buildvcs=false" $(GOMOBILE) bind \
	  -tags with_gvisor \
	  -target macos/arm64,ios/arm64,iossimulator/arm64 \
	  -trimpath -ldflags="-s -w" \
	  -o Libgost.xcframework \
	  .
```
并把 `.PHONY` 与 `clean` 里的 `Libgost.xcframework` 处理保持不变（改注释即可）。

根 `Makefile`：整段替换 Apple 相关目标为：

```make
.PHONY: all android android-release macos macos-release ios ios-sim ios-release clean apple-lib

FORCE:

apple-lib: FORCE
	cd libgost && $(MAKE) apple-xcframework
	mkdir -p apple/Frameworks
	rm -rf apple/Frameworks/Libgost.xcframework
	cp -R libgost/Libgost.xcframework apple/Frameworks/

apple/Frameworks/Libgost.xcframework: apple-lib

macos: apple/Frameworks/Libgost.xcframework
	cd apple && xcodebuild -project GostX.xcodeproj -scheme GostX-macOS \
	  -configuration Release -derivedDataPath build ONLY_ACTIVE_ARCH=YES \
	  -allowProvisioningUpdates build

macos-release: apple/Frameworks/Libgost.xcframework
	cd apple && \
		xcodebuild archive -project GostX.xcodeproj -scheme GostX-macOS \
			-configuration Release -archivePath build/GostX-macOS.xcarchive \
			-destination 'platform=macOS' && \
		xcodebuild -exportArchive -archivePath build/GostX-macOS.xcarchive \
			-exportOptionsPlist Configuration/ExportOptions.plist \
			-exportPath build/Release && \
		hdiutil create -volname GostX -srcfolder build/Release/GostX.app \
			-ov -format UDZO build/GostX.dmg

ios: apple/Frameworks/Libgost.xcframework
	cd apple && xcodebuild -project GostX.xcodeproj -scheme GostX-iOS \
	  -configuration Debug -destination 'generic/platform=iOS' \
	  -derivedDataPath build -allowProvisioningUpdates build

ios-sim: apple/Frameworks/Libgost.xcframework
	cd apple && xcodebuild -project GostX.xcodeproj -scheme GostX-iOS \
	  -configuration Debug -destination 'generic/platform=iOS Simulator' \
	  -derivedDataPath build build

ios-release: apple/Frameworks/Libgost.xcframework
	cd apple && \
		xcodebuild archive -project GostX.xcodeproj -scheme GostX-iOS \
			-configuration Release -archivePath build/GostX-iOS.xcarchive \
			-destination 'generic/platform=iOS' && \
		xcodebuild -exportArchive -archivePath build/GostX-iOS.xcarchive \
			-exportOptionsPlist Configuration/ExportOptions-appstore.plist \
			-exportPath build/Release-ios

clean:
	cd libgost && $(MAKE) clean
	cd android && ./gradlew clean 2>/dev/null || true
	rm -f android/app/libs/libgost.aar
	rm -rf apple/Frameworks/Libgost.xcframework apple/build
```

保留原有 `all: android`、`android`、`android-release`、`build-libgost-aar`、`bundleRelease` 相关行不动。

- [ ] **Step 7: 更新 `.gitignore` 与文档**

`.gitignore`：把

```
macos/Configuration/*.xcconfig
!macos/Configuration/*.example.xcconfig
macos/Frameworks/
macos/build/
```
改为
```
apple/Configuration/*.xcconfig
!apple/Configuration/*.example.xcconfig
apple/Frameworks/
apple/build/
```

`AGENTS.md`：把「Android (`android/`)」小节里的 macOS 路径描述改为 `apple/` 结构，并新增一句：

```
- **Apple (`apple/`)** — 单个 Xcode 工程，4 个 target：`GostX-macOS`、`GostX-iOS`、`GostXTunnel-macOS`、`GostXTunnel-iOS`。平台无关 Swift 在 `apple/Shared/{Common,App,Tunnel}`，以 synchronized folder 加入 target。
```

`README.md` 的 Build 段落改成：

```bash
make android          # Debug APK
make android-release  # Release APK + AAB
make macos            # macOS app（需要 Apple 开发者签名）
make ios              # iOS app（真机）
make ios-sim          # iOS 模拟器（免签名）
```

- [ ] **Step 8: 构建并人工回归 macOS**

```bash
cd /Users/kbliu/Workspace/project/GostX
make macos 2>&1 | tail -20
```
Expected: `BUILD SUCCEEDED`，产物在 `apple/build/Build/Products/Release/GostX.app`。

人工验证（启动 `open apple/build/Build/Products/Release/GostX.app`）：起停 VPN、编辑并切换 profile、导入文件、日志显示与级别切换四项正常。

- [ ] **Step 9: 提交**

```bash
git add -A
git commit -m "build(apple): move clients into apple/ and share sources via synchronized folders"
```

---

### Task 2: libgost 可配置内存上限

**Files:**
- Modify: `libgost/libgost.go:590-617`
- Test: `libgost/libgost_test.go`（在 `TestSetMemoryLimit` 之后追加）

**Interfaces:**
- Consumes: 现有 `SetMemoryLimit(enabled bool)`
- Produces: `func SetMemoryLimitBytes(bytes int64)` —— gomobile 导出为 Swift 的 `LibgostSetMemoryLimitBytes(_ bytes: Int64)`。

- [ ] **Step 1: 写失败测试**

在 `libgost/libgost_test.go` 的 `TestSetMemoryLimit` 之后追加：

```go
func TestSetMemoryLimitBytes(t *testing.T) {
	defer runtimeDebug.SetGCPercent(100)
	defer runtimeDebug.SetMemoryLimit(math.MaxInt64)

	SetMemoryLimitBytes(30 * 1024 * 1024)
	prev := runtimeDebug.SetGCPercent(50)
	runtimeDebug.SetGCPercent(prev)
	if prev != 50 {
		t.Errorf("SetMemoryLimitBytes(30MB): GOGC = %d, want 50", prev)
	}

	// bytes <= 0 表示不限制：GOGC 回到 100，MemoryLimit 回到 MaxInt64。
	SetMemoryLimitBytes(0)
	prev = runtimeDebug.SetGCPercent(100)
	runtimeDebug.SetGCPercent(prev)
	if prev != 100 {
		t.Errorf("SetMemoryLimitBytes(0): GOGC = %d, want 100", prev)
	}

	SetMemoryLimitBytes(-1)
	if got := runtimeDebug.SetMemoryLimit(math.MaxInt64); got != math.MaxInt64 {
		t.Errorf("SetMemoryLimitBytes(-1): memory limit = %d, want MaxInt64", got)
	}
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /Users/kbliu/Workspace/project/GostX/libgost && go test -run TestSetMemoryLimitBytes .
```
Expected: 编译失败，`undefined: SetMemoryLimitBytes`。

- [ ] **Step 3: 实现**

把 `libgost/libgost.go` 中的 `SetMemoryLimit` 替换为：

```go
// SetMemoryLimit configures the Go runtime GC for mobile background use.
// enabled=true 时使用 100 MB 软内存上限 + GOGC=50（Android 默认值）。
// iOS 的 Network Extension 内存上限更低，应改用 SetMemoryLimitBytes。
func SetMemoryLimit(enabled bool) {
	if enabled {
		SetMemoryLimitBytes(100 * 1024 * 1024)
		return
	}
	SetMemoryLimitBytes(0)
}

// SetMemoryLimitBytes 以字节为单位设置 Go 运行时软内存上限。
// bytes <= 0 表示不限制（MemoryLimit=MaxInt64），GOGC 回到默认 100。
func SetMemoryLimitBytes(bytes int64) {
	if bytes <= 0 {
		runtimeDebug.SetGCPercent(100)
		runtimeDebug.SetMemoryLimit(math.MaxInt64)
		return
	}
	runtimeDebug.SetGCPercent(50)
	runtimeDebug.SetMemoryLimit(bytes)
}
```

- [ ] **Step 4: 运行全部 Go 测试**

```bash
cd /Users/kbliu/Workspace/project/GostX/libgost && go test .
```
Expected: `ok  github.com/lkebin/gostx/libgost`。

- [ ] **Step 5: 提交**

```bash
git add libgost/libgost.go libgost/libgost_test.go
git commit -m "feat(libgost): add SetMemoryLimitBytes for platform-specific heap caps"
```

---

### Task 3: 共享层新增与改动

**Files:**
- Create: `apple/Shared/App/Clipboard.swift`
- Create: `apple/Shared/App/GostDefaults.swift`
- Create: `apple/Shared/App/ProfileNameValidator.swift`
- Create: `apple/Tests/SharedConfigTests.swift`
- Modify: `apple/Shared/Common/AppGroupConfig.swift`
- Modify: `apple/Shared/App/AppLogger.swift`
- Modify: `apple/Shared/App/FileManageViewModel.swift`
- Modify: `apple/Shared/App/LogViewModel.swift`
- Modify: `apple/Shared/App/ConfigRepository.swift`
- Modify: `apple/Shared/Tunnel/PacketTunnelProvider.swift`

**Interfaces:**
- Consumes: `AppGroupConfig.containerURL`、`AppGroupConfig.logLevel`、`ConfigRepository.profiles`
- Produces:
  - `enum Clipboard { static func copy(_ text: String) }`
  - `enum GostDefaults { static var yaml: String }`
  - `enum ProfileNameValidator { static func error(name: String, existing: Set<String>) -> String? }`
  - `AppGroupConfig.logMaxSizeKB: Int`（默认 2048）
  - `AppGroupConfig.logMaxSizeOptions: [Int]`（`[512, 1024, 2048, 5120]`）

- [ ] **Step 1: 写失败测试**

创建 `apple/Tests/SharedConfigTests.swift`：

```swift
import XCTest
@testable import GostX

final class GostDefaultsTests: XCTestCase {
    func testDefaultYAMLMatchesPlatformMode() {
        #if os(iOS)
        XCTAssertTrue(GostDefaults.yaml.contains("type: tungo"),
                      "iOS 默认配置必须是 tungo 模板")
        #else
        XCTAssertTrue(GostDefaults.yaml.contains("type: socks5"),
                      "macOS 默认配置保持 socks5 模板")
        XCTAssertFalse(GostDefaults.yaml.contains("type: tungo"))
        #endif
    }
}

final class ProfileNameValidatorTests: XCTestCase {
    func testEmptyNameRejected() {
        XCTAssertNotNil(ProfileNameValidator.error(name: "  ", existing: []))
    }

    func testDuplicateNameRejected() {
        XCTAssertNotNil(ProfileNameValidator.error(name: "Default", existing: ["Default"]))
    }

    func testCommaRejected() {
        XCTAssertNotNil(ProfileNameValidator.error(name: "a,b", existing: []))
    }

    func testValidNameAccepted() {
        XCTAssertNil(ProfileNameValidator.error(name: "Office", existing: ["Default"]))
    }
}

final class AppGroupLogSizeTests: XCTestCase {
    func testDefaultLogMaxSizeIs2048KB() {
        let key = "log_max_size_kb"
        UserDefaults(suiteName: AppGroupConfig.groupId)?.removeObject(forKey: key)
        XCTAssertEqual(AppGroupConfig.logMaxSizeKB, 2048)
    }

    func testLogMaxSizeOptionsMatchAndroid() {
        XCTAssertEqual(AppGroupConfig.logMaxSizeOptions, [512, 1024, 2048, 5120])
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /Users/kbliu/Workspace/project/GostX
xcodebuild test -project apple/GostX.xcodeproj -scheme GostX-macOS \
  -destination 'platform=macOS' 2>&1 | grep -E "error:|TEST FAILED" | head
```
Expected: 编译失败，提示 `cannot find 'GostDefaults' in scope`、`cannot find 'ProfileNameValidator' in scope`、`value of type 'AppGroupConfig' has no member 'logMaxSizeKB'`。

- [ ] **Step 3: 新增三个共享文件**

`apple/Shared/App/Clipboard.swift`：

```swift
import Foundation
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// 跨平台剪贴板封装：macOS 用 NSPasteboard，iOS 用 UIPasteboard。
enum Clipboard {
    static func copy(_ text: String) {
        #if os(macOS)
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        #else
        UIPasteboard.general.string = text
        #endif
    }
}
```

`apple/Shared/App/GostDefaults.swift`：

```swift
import Foundation

/// 各平台的默认 profile 配置。
/// macOS 保留进程内代理模式可用的 socks5 模板；iOS 只有 VPN 模式，
/// 因此默认配置必须是 tungo 服务（与 config.yaml.example 的结构一致）。
enum GostDefaults {
    static var yaml: String {
        #if os(iOS)
        return vpnTemplate
        #else
        return proxyTemplate
        #endif
    }

    static let vpnTemplate = """
    services:
      - name: vpn
        handler:
          type: tungo
          chain: upstream

    chains:
      - name: upstream
        hops:
          - name: hop0
            nodes:
              - name: server
                addr: example.com
                connector:
                  type: ss
                  metadata:
                    method: chacha20-ietf-poly1305
                    password: password
                dialer:
                  type: tcp
    """

    static let proxyTemplate = """
    services:
      - name: socks5-outbound
        addr: :1080
        handler:
          type: socks5
        listener:
          type: tcp
    """
}
```

`apple/Shared/App/ProfileNameValidator.swift`：

```swift
import Foundation

/// profile 名校验，与 Android 的规则保持一致：
/// 非空、不含逗号、不与现有 profile 重名。
enum ProfileNameValidator {
    static func error(name: String, existing: Set<String>) -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty {
            return NSLocalizedString("Profile name can't be empty.", comment: "")
        }
        if trimmed.contains(",") {
            return NSLocalizedString("Profile name can't contain a comma.", comment: "")
        }
        if existing.contains(trimmed) {
            return NSLocalizedString("A profile with that name already exists.", comment: "")
        }
        return nil
    }
}
```

- [ ] **Step 4: 修改共享文件**

1）`apple/Shared/Common/AppGroupConfig.swift`，在 `logLevelOptions` 之后追加：

```swift
    static let logMaxSizeOptions = [512, 1024, 2048, 5120]

    static var logMaxSizeKB: Int {
        get {
            let value = sharedDefaults?.integer(forKey: "log_max_size_kb") ?? 0
            return value > 0 ? value : 2048
        }
        set { sharedDefaults?.set(newValue, forKey: "log_max_size_kb") }
    }
```

2）`apple/Shared/App/AppLogger.swift`：删除常量 `private static let maxLogBytes = 2 * 1024 * 1024` 与 `rotateKeepBytes`，改为在 `writeToFile` 中：

```swift
                let maxBytes = off_t(AppGroupConfig.logMaxSizeKB) * 1024
                if sizeBefore + off_t(written) > maxBytes {
                    rotate(path: path, keepBytes: maxBytes / 2)
                }
```
并把 `rotate(path:)` 签名改为 `rotate(path: String, keepBytes: off_t)`，其内部使用 `keepBytes` 代替原 `rotateKeepBytes`。

3）`apple/Shared/App/LogViewModel.swift` 与 `FileManageViewModel.swift`：把 `NSPasteboard.general.clearContents(); NSPasteboard.general.setString(x, forType: .string)` 分别替换为 `Clipboard.copy(x)`；删除 `import AppKit`（`Clipboard.swift` 内部已按平台 import）。

4）`apple/Shared/App/ConfigRepository.swift`：把所有 `defaultGostYAML` 引用替换为 `GostDefaults.yaml`（共 4 处：`Profile` 的 init 默认参数、`ensureDefaultProfile`、`getConfig` 兜底、`activeConfig` 兜底）。

5）`apple/Shared/App/Arguments.swift`：保留文件，仅保留 key 常量与 `defaultGostYAML`（macOS 旧逻辑仍在用）；新增一行注释说明 iOS 使用 `GostDefaults.yaml`。

6）`apple/Shared/Tunnel/PacketTunnelProvider.swift`：把

```swift
        LibgostSetLogMaxSize(2 * 1024 * 1024)
```
改为

```swift
        LibgostSetLogMaxSize(AppGroupConfig.logMaxSizeKB * 1024)
```

- [ ] **Step 5: 运行测试确认通过**

```bash
cd /Users/kbliu/Workspace/project/GostX
xcodebuild test -project apple/GostX.xcodeproj -scheme GostX-macOS \
  -destination 'platform=macOS' 2>&1 | tail -5
```
Expected: `** TEST SUCCEEDED **`。

- [ ] **Step 6: macOS 手工回归**

`make macos` 后启动 app，验证日志级别切换与"清空日志"仍可用（`AppLogger` 上限逻辑改动过），并确认日志文件大小超过 2 MB 时会被截断到约 1 MB。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "feat(apple): share clipboard, defaults and log size across clients"
```

---

### Task 4: 新增 iOS target 骨架

**Files:**
- Modify: `apple/GostX.xcodeproj/project.pbxproj`
- Create: `apple/GostX.xcodeproj/xcshareddata/xcschemes/GostX-iOS.xcscheme`
- Create: `apple/iOS/GostXApp.swift`、`apple/iOS/HomeView.swift`（占位）、`apple/iOS/Route.swift`
- Create: `apple/iOS/Info.plist`、`apple/iOS/GostX-iOS.entitlements`
- Create: `apple/iOS/Assets.xcassets/Contents.json`、`AppIcon.appiconset/Contents.json`、`AccentColor.colorset/Contents.json`
- Create: `apple/Tunnel/iOS/Info.plist`、`apple/Tunnel/iOS/GostXTunnel.entitlements`
- Create: `apple/Configuration/ExportOptions-appstore.plist`

**Interfaces:**
- Consumes: Task 1 的 `Shared/{Common,App,Tunnel}` 同步组对象 ID（`A1000000000000000000000E`、`A1000000000000000000000F`、`B2000000000000000000000B`）
- Produces: target `GostX-iOS`（product `GostX.app`，module `GostX`）、`GostXTunnel-iOS`（product `GostXTunnel.appex`，module `GostXTunnel`）、`GostX-iOSTests`；scheme `GostX-iOS`；`enum Route` 与 `GostXApp`。

- [ ] **Step 1: 创建 iOS target 的资源与占位源文件**

`apple/iOS/Info.plist`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleDisplayName</key>
	<string>GostX</string>
	<key>CFBundleIdentifier</key>
	<string>$(PRODUCT_BUNDLE_IDENTIFIER)</string>
	<key>CFBundleName</key>
	<string>$(PRODUCT_NAME)</string>
	<key>CFBundlePackageType</key>
	<string>$(PRODUCT_BUNDLE_PACKAGE_TYPE)</string>
	<key>CFBundleShortVersionString</key>
	<string>$(MARKETING_VERSION)</string>
	<key>CFBundleVersion</key>
	<string>$(CURRENT_PROJECT_VERSION)</string>
	<key>UILaunchScreen</key>
	<dict/>
	<key>UISupportedInterfaceOrientations</key>
	<array>
		<string>UIInterfaceOrientationPortrait</string>
	</array>
</dict>
</plist>
```

`apple/iOS/GostX-iOS.entitlements`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>com.apple.security.application-groups</key>
	<array>
		<string>group.cn.liukebin.gostx</string>
	</array>
	<key>com.apple.developer.networking.networkextension</key>
	<array>
		<string>packet-tunnel-provider</string>
	</array>
</dict>
</plist>
```

`apple/Tunnel/iOS/Info.plist`：内容与 macOS 扩展相同的 `NSExtension` 结构：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleDisplayName</key>
	<string>GostX Tunnel</string>
	<key>CFBundleIdentifier</key>
	<string>$(PRODUCT_BUNDLE_IDENTIFIER)</string>
	<key>CFBundleName</key>
	<string>$(PRODUCT_NAME)</string>
	<key>CFBundlePackageType</key>
	<string>$(PRODUCT_BUNDLE_PACKAGE_TYPE)</string>
	<key>CFBundleShortVersionString</key>
	<string>$(MARKETING_VERSION)</string>
	<key>CFBundleVersion</key>
	<string>$(CURRENT_PROJECT_VERSION)</string>
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

`apple/Tunnel/iOS/GostXTunnel.entitlements`：与 iOS app 的 entitlements 内容一致（app group + packet-tunnel-provider）。

`apple/iOS/Assets.xcassets/Contents.json`：

```json
{ "info" : { "author" : "xcode", "version" : 1 } }
```

`apple/iOS/Assets.xcassets/AccentColor.colorset/Contents.json`：

```json
{
  "colors" : [ { "idiom" : "universal" } ],
  "info" : { "author" : "xcode", "version" : 1 }
}
```

`apple/iOS/Assets.xcassets/AppIcon.appiconset/Contents.json`（先把仓库根目录的图标复制成 1024 单尺寸）：

```bash
cp Icon-iOS-Default-1024x1024@1x.png apple/iOS/Assets.xcassets/AppIcon.appiconset/icon-1024.png
```

```json
{
  "images" : [
    { "filename" : "icon-1024.png", "idiom" : "universal", "platform" : "ios", "size" : "1024x1024" }
  ],
  "info" : { "author" : "xcode", "version" : 1 }
}
```

`apple/iOS/Route.swift`：

```swift
import Foundation

enum Route: Hashable {
    case configEdit(profileId: String)
    case logs
    case settings
    case fileManage
}
```

`apple/iOS/HomeView.swift`（占位，Task 5 替换）：

```swift
import SwiftUI

struct HomeView: View {
    var body: some View {
        Text("GostX")
    }
}
```

`apple/iOS/GostXApp.swift`：

```swift
import SwiftUI

@main
struct GostXApp: App {
    @StateObject private var vpn = VpnManager.shared

    var body: some Scene {
        WindowGroup {
            HomeView()
                .task {
                    // hosted 单元测试会启动 App，不需要也无权初始化 Network Extension。
                    if ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil {
                        await vpn.setup()
                    }
                }
        }
    }
}
```

`apple/Configuration/ExportOptions-appstore.plist`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>method</key>
	<string>app-store-connect</string>
	<key>signingStyle</key>
	<string>automatic</string>
	<key>uploadSymbols</key>
	<true/>
	<key>destination</key>
	<string>export</string>
</dict>
</plist>
```

- [ ] **Step 2: 在工程里加同步组与 iOS 产品引用**

在 `project.pbxproj` 的 `PBXFileReference` 段追加：

```
		A10000000000000000000002 /* GostX.app */ = {isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = GostX.app; sourceTree = BUILT_PRODUCTS_DIR; };
		B20000000000000000000002 /* GostXTunnel.appex */ = {isa = PBXFileReference; explicitFileType = "wrapper.app-extension"; includeInIndex = 0; path = GostXTunnel.appex; sourceTree = BUILT_PRODUCTS_DIR; };
		C30000000000000000000002 /* GostX-iOSTests.xctest */ = {isa = PBXFileReference; explicitFileType = wrapper.cfbundle; includeInIndex = 0; path = "GostX-iOSTests.xctest"; sourceTree = BUILT_PRODUCTS_DIR; };
```

在 `PBXFileSystemSynchronizedRootGroup` 段追加：

```
		A10000000000000000000010 /* iOS */ = {isa = PBXFileSystemSynchronizedRootGroup; exceptions = (); explicitFileTypes = {}; explicitFolders = (); path = iOS; sourceTree = "<group>"; };
		C3000000000000000000000A /* Tests */ = {isa = PBXFileSystemSynchronizedRootGroup; exceptions = (); explicitFileTypes = {}; explicitFolders = (); path = Tests; sourceTree = "<group>"; };
```
给 `iOS` 组加 Info.plist 例外（否则会被当资源打包）：

```
/* Begin PBXFileSystemSynchronizedBuildFileExceptionSet section */
		A10000000000000000000011 /* PBXFileSystemSynchronizedBuildFileExceptionSet */ = {
			isa = PBXFileSystemSynchronizedBuildFileExceptionSet;
			membershipExceptions = (
				Info.plist,
			);
			target = A10000000000000000000001 /* GostX-iOS */;
		};
/* End PBXFileSystemSynchronizedBuildFileExceptionSet section */
```
并把 `A10000000000000000000010` 的 `exceptions = ();` 改为 `exceptions = (A10000000000000000000011 /* PBXFileSystemSynchronizedBuildFileExceptionSet */, );`。

- [ ] **Step 3: 加 iOS app / 扩展 / 测试 target 的对象块**

`PBXBuildFile` 段追加：

```
		A1000000000000000000000C /* Libgost.xcframework in Frameworks */ = {isa = PBXBuildFile; fileRef = 4303495C3004D78100FD9EE9 /* Libgost.xcframework */; };
		A1000000000000000000000D /* GostXTunnel.appex in Embed Foundation Extensions */ = {isa = PBXBuildFile; fileRef = B20000000000000000000002 /* GostXTunnel.appex */; settings = {ATTRIBUTES = (RemoveHeadersOnCopy, ); }; };
		B20000000000000000000009 /* Libgost.xcframework in Frameworks */ = {isa = PBXBuildFile; fileRef = 4303495C3004D78100FD9EE9 /* Libgost.xcframework */; };
		B2000000000000000000000A /* NetworkExtension.framework in Frameworks */ = {isa = PBXBuildFile; fileRef = 430349433004D3A000FD9EE9 /* NetworkExtension.framework */; };
```

`PBXContainerItemProxy` 段追加（app → 扩展、测试 → app）：

```
		A1000000000000000000000B /* PBXContainerItemProxy */ = {
			isa = PBXContainerItemProxy;
			containerPortal = B532A65E285B528900159BAC /* Project object */;
			proxyType = 1;
			remoteGlobalIDString = B20000000000000000000001;
			remoteInfo = "GostXTunnel-iOS";
		};
		C30000000000000000000009 /* PBXContainerItemProxy */ = {
			isa = PBXContainerItemProxy;
			containerPortal = B532A65E285B528900159BAC /* Project object */;
			proxyType = 1;
			remoteGlobalIDString = A10000000000000000000001;
			remoteInfo = "GostX-iOS";
		};
```

`PBXCopyFilesBuildPhase` 段追加：

```
		A10000000000000000000006 /* Embed Foundation Extensions */ = {
			isa = PBXCopyFilesBuildPhase;
			buildActionMask = 2147483647;
			dstPath = "";
			dstSubfolderSpec = 13;
			files = (
				A1000000000000000000000D /* GostXTunnel.appex in Embed Foundation Extensions */,
			);
			name = "Embed Foundation Extensions";
			runOnlyForDeploymentPostprocessing = 0;
		};
```

`PBXFrameworksBuildPhase` 段追加：

```
		A10000000000000000000004 /* Frameworks */ = {
			isa = PBXFrameworksBuildPhase;
			buildActionMask = 2147483647;
			files = (
				A1000000000000000000000C /* Libgost.xcframework in Frameworks */,
			);
			runOnlyForDeploymentPostprocessing = 0;
		};
		B20000000000000000000004 /* Frameworks */ = {
			isa = PBXFrameworksBuildPhase;
			buildActionMask = 2147483647;
			files = (
				B20000000000000000000009 /* Libgost.xcframework in Frameworks */,
				B2000000000000000000000A /* NetworkExtension.framework in Frameworks */,
			);
			runOnlyForDeploymentPostprocessing = 0;
		};
		C30000000000000000000004 /* Frameworks */ = {
			isa = PBXFrameworksBuildPhase;
			buildActionMask = 2147483647;
			files = (
			);
			runOnlyForDeploymentPostprocessing = 0;
		};
```

`PBXResourcesBuildPhase` 段追加：

```
		A10000000000000000000005 /* Resources */ = {
			isa = PBXResourcesBuildPhase;
			buildActionMask = 2147483647;
			files = (
			);
			runOnlyForDeploymentPostprocessing = 0;
		};
```

`PBXSourcesBuildPhase` 段追加：

```
		A10000000000000000000003 /* Sources */ = {
			isa = PBXSourcesBuildPhase;
			buildActionMask = 2147483647;
			files = (
			);
			runOnlyForDeploymentPostprocessing = 0;
		};
		B20000000000000000000003 /* Sources */ = {
			isa = PBXSourcesBuildPhase;
			buildActionMask = 2147483647;
			files = (
			);
			runOnlyForDeploymentPostprocessing = 0;
		};
		C30000000000000000000003 /* Sources */ = {
			isa = PBXSourcesBuildPhase;
			buildActionMask = 2147483647;
			files = (
			);
			runOnlyForDeploymentPostprocessing = 0;
		};
```

`PBXNativeTarget` 段追加：

```
		A10000000000000000000001 /* GostX-iOS */ = {
			isa = PBXNativeTarget;
			buildConfigurationList = A10000000000000000000007 /* Build configuration list for PBXNativeTarget "GostX-iOS" */;
			buildPhases = (
				A10000000000000000000003 /* Sources */,
				A10000000000000000000004 /* Frameworks */,
				A10000000000000000000005 /* Resources */,
				A10000000000000000000006 /* Embed Foundation Extensions */,
			);
			buildRules = (
			);
			dependencies = (
				A1000000000000000000000A /* PBXTargetDependency */,
			);
			fileSystemSynchronizedGroups = (
				A1000000000000000000000E /* Common */,
				A1000000000000000000000F /* App */,
				A10000000000000000000010 /* iOS */,
			);
			name = "GostX-iOS";
			productName = GostX;
			productReference = A10000000000000000000002 /* GostX.app */;
			productType = "com.apple.product-type.application";
		};
		B20000000000000000000001 /* GostXTunnel-iOS */ = {
			isa = PBXNativeTarget;
			buildConfigurationList = B20000000000000000000006 /* Build configuration list for PBXNativeTarget "GostXTunnel-iOS" */;
			buildPhases = (
				B20000000000000000000003 /* Sources */,
				B20000000000000000000004 /* Frameworks */,
			);
			buildRules = (
			);
			dependencies = (
			);
			fileSystemSynchronizedGroups = (
				A1000000000000000000000E /* Common */,
				B2000000000000000000000B /* Tunnel */,
			);
			name = "GostXTunnel-iOS";
			productName = GostXTunnel;
			productReference = B20000000000000000000002 /* GostXTunnel.appex */;
			productType = "com.apple.product-type.app-extension";
		};
		C30000000000000000000001 /* GostX-iOSTests */ = {
			isa = PBXNativeTarget;
			buildConfigurationList = C30000000000000000000005 /* Build configuration list for PBXNativeTarget "GostX-iOSTests" */;
			buildPhases = (
				C30000000000000000000003 /* Sources */,
				C30000000000000000000004 /* Frameworks */,
			);
			buildRules = (
			);
			dependencies = (
				C30000000000000000000008 /* PBXTargetDependency */,
			);
			fileSystemSynchronizedGroups = (
				C3000000000000000000000A /* Tests */,
			);
			name = "GostX-iOSTests";
			productName = "GostX-iOSTests";
			productReference = C30000000000000000000002 /* GostX-iOSTests.xctest */;
			productType = "com.apple.product-type.bundle.unit-test";
		};
```

`PBXTargetDependency` 段追加：

```
		A1000000000000000000000A /* PBXTargetDependency */ = {
			isa = PBXTargetDependency;
			target = B20000000000000000000001 /* GostXTunnel-iOS */;
			targetProxy = A1000000000000000000000B /* PBXContainerItemProxy */;
		};
		C30000000000000000000008 /* PBXTargetDependency */ = {
			isa = PBXTargetDependency;
			target = A10000000000000000000001 /* GostX-iOS */;
			targetProxy = C30000000000000000000009 /* PBXContainerItemProxy */;
		};
```

- [ ] **Step 4: 加 iOS 三组 build configuration**

`XCBuildConfiguration` 段追加（`GostX-iOS` Debug/Release、`GostXTunnel-iOS` Debug/Release、`GostX-iOSTests` Debug/Release）：

```
		A10000000000000000000008 /* Debug */ = {
			isa = XCBuildConfiguration;
			baseConfigurationReference = AD12003E3005000000FD9EE9 /* DevelopmentTeam.xcconfig */;
			buildSettings = {
				ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;
				ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME = AccentColor;
				CODE_SIGN_ENTITLEMENTS = "iOS/GostX-iOS.entitlements";
				CODE_SIGN_STYLE = Automatic;
				CURRENT_PROJECT_VERSION = 1;
				ENABLE_PREVIEWS = YES;
				GENERATE_INFOPLIST_FILE = NO;
				INFOPLIST_FILE = iOS/Info.plist;
				IPHONEOS_DEPLOYMENT_TARGET = 17.0;
				LD_RUNPATH_SEARCH_PATHS = (
					"$(inherited)",
					"@executable_path/Frameworks",
				);
				MARKETING_VERSION = 1.0.0;
				PRODUCT_BUNDLE_IDENTIFIER = cn.liukebin.gostx;
				PRODUCT_MODULE_NAME = GostX;
				PRODUCT_NAME = GostX;
				SDKROOT = iphoneos;
				SUPPORTS_MACCATALYST = NO;
				SWIFT_EMIT_LOC_STRINGS = YES;
				SWIFT_VERSION = 5.0;
				TARGETED_DEVICE_FAMILY = "1,2";
			};
			name = Debug;
		};
		A10000000000000000000009 /* Release */ = {
			isa = XCBuildConfiguration;
			baseConfigurationReference = AD12003E3005000000FD9EE9 /* DevelopmentTeam.xcconfig */;
			buildSettings = {
				ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;
				ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME = AccentColor;
				CODE_SIGN_ENTITLEMENTS = "iOS/GostX-iOS.entitlements";
				CODE_SIGN_STYLE = Automatic;
				CURRENT_PROJECT_VERSION = 1;
				ENABLE_PREVIEWS = YES;
				GENERATE_INFOPLIST_FILE = NO;
				INFOPLIST_FILE = iOS/Info.plist;
				IPHONEOS_DEPLOYMENT_TARGET = 17.0;
				LD_RUNPATH_SEARCH_PATHS = (
					"$(inherited)",
					"@executable_path/Frameworks",
				);
				MARKETING_VERSION = 1.0.0;
				PRODUCT_BUNDLE_IDENTIFIER = cn.liukebin.gostx;
				PRODUCT_MODULE_NAME = GostX;
				PRODUCT_NAME = GostX;
				SDKROOT = iphoneos;
				SUPPORTS_MACCATALYST = NO;
				SWIFT_COMPILATION_MODE = wholemodule;
				SWIFT_VERSION = 5.0;
				TARGETED_DEVICE_FAMILY = "1,2";
				VALIDATE_PRODUCT = YES;
			};
			name = Release;
		};
		B20000000000000000000007 /* Debug */ = {
			isa = XCBuildConfiguration;
			baseConfigurationReference = AD12003E3005000000FD9EE9 /* DevelopmentTeam.xcconfig */;
			buildSettings = {
				CODE_SIGN_ENTITLEMENTS = "Tunnel/iOS/GostXTunnel.entitlements";
				CODE_SIGN_STYLE = Automatic;
				CURRENT_PROJECT_VERSION = 1;
				GENERATE_INFOPLIST_FILE = NO;
				INFOPLIST_FILE = Tunnel/iOS/Info.plist;
				IPHONEOS_DEPLOYMENT_TARGET = 17.0;
				MARKETING_VERSION = 1.0.0;
				PRODUCT_BUNDLE_IDENTIFIER = cn.liukebin.gostx.tunnel;
				PRODUCT_MODULE_NAME = GostXTunnel;
				PRODUCT_NAME = GostXTunnel;
				SDKROOT = iphoneos;
				SKIP_INSTALL = YES;
				SWIFT_VERSION = 5.0;
				TARGETED_DEVICE_FAMILY = "1,2";
			};
			name = Debug;
		};
		B20000000000000000000008 /* Release */ = {
			isa = XCBuildConfiguration;
			baseConfigurationReference = AD12003E3005000000FD9EE9 /* DevelopmentTeam.xcconfig */;
			buildSettings = {
				CODE_SIGN_ENTITLEMENTS = "Tunnel/iOS/GostXTunnel.entitlements";
				CODE_SIGN_STYLE = Automatic;
				CURRENT_PROJECT_VERSION = 1;
				GENERATE_INFOPLIST_FILE = NO;
				INFOPLIST_FILE = Tunnel/iOS/Info.plist;
				IPHONEOS_DEPLOYMENT_TARGET = 17.0;
				MARKETING_VERSION = 1.0.0;
				PRODUCT_BUNDLE_IDENTIFIER = cn.liukebin.gostx.tunnel;
				PRODUCT_MODULE_NAME = GostXTunnel;
				PRODUCT_NAME = GostXTunnel;
				SDKROOT = iphoneos;
				SKIP_INSTALL = YES;
				SWIFT_COMPILATION_MODE = wholemodule;
				SWIFT_VERSION = 5.0;
				TARGETED_DEVICE_FAMILY = "1,2";
				VALIDATE_PRODUCT = YES;
			};
			name = Release;
		};
		C30000000000000000000006 /* Debug */ = {
			isa = XCBuildConfiguration;
			buildSettings = {
				BUNDLE_LOADER = "$(TEST_HOST)";
				CODE_SIGN_STYLE = Automatic;
				CURRENT_PROJECT_VERSION = 1;
				GENERATE_INFOPLIST_FILE = YES;
				IPHONEOS_DEPLOYMENT_TARGET = 17.0;
				MARKETING_VERSION = 1.0;
				PRODUCT_BUNDLE_IDENTIFIER = "cn.liukebin.gostx.iOSTests";
				PRODUCT_NAME = "$(TARGET_NAME)";
				SDKROOT = iphoneos;
				SWIFT_VERSION = 5.0;
				TARGETED_DEVICE_FAMILY = "1,2";
				TEST_HOST = "$(BUILT_PRODUCTS_DIR)/GostX.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/GostX";
			};
			name = Debug;
		};
		C30000000000000000000007 /* Release */ = {
			isa = XCBuildConfiguration;
			buildSettings = {
				BUNDLE_LOADER = "$(TEST_HOST)";
				CODE_SIGN_STYLE = Automatic;
				CURRENT_PROJECT_VERSION = 1;
				GENERATE_INFOPLIST_FILE = YES;
				IPHONEOS_DEPLOYMENT_TARGET = 17.0;
				MARKETING_VERSION = 1.0;
				PRODUCT_BUNDLE_IDENTIFIER = "cn.liukebin.gostx.iOSTests";
				PRODUCT_NAME = "$(TARGET_NAME)";
				SDKROOT = iphoneos;
				SWIFT_VERSION = 5.0;
				TARGETED_DEVICE_FAMILY = "1,2";
				TEST_HOST = "$(BUILT_PRODUCTS_DIR)/GostX.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/GostX";
			};
			name = Release;
		};
```

`XCConfigurationList` 段追加：

```
		A10000000000000000000007 /* Build configuration list for PBXNativeTarget "GostX-iOS" */ = {
			isa = XCConfigurationList;
			buildConfigurations = (
				A10000000000000000000008 /* Debug */,
				A10000000000000000000009 /* Release */,
			);
			defaultConfigurationIsVisible = 0;
			defaultConfigurationName = Release;
		};
		B20000000000000000000006 /* Build configuration list for PBXNativeTarget "GostXTunnel-iOS" */ = {
			isa = XCConfigurationList;
			buildConfigurations = (
				B20000000000000000000007 /* Debug */,
				B20000000000000000000008 /* Release */,
			);
			defaultConfigurationIsVisible = 0;
			defaultConfigurationName = Release;
		};
		C30000000000000000000005 /* Build configuration list for PBXNativeTarget "GostX-iOSTests" */ = {
			isa = XCConfigurationList;
			buildConfigurations = (
				C30000000000000000000006 /* Debug */,
				C30000000000000000000007 /* Release */,
			);
			defaultConfigurationIsVisible = 0;
			defaultConfigurationName = Release;
		};
```

- [ ] **Step 5: 注册到工程对象与产品组**

`PBXProject` 的 `targets` 列表追加：

```
				A10000000000000000000001 /* GostX-iOS */,
				B20000000000000000000001 /* GostXTunnel-iOS */,
				C30000000000000000000001 /* GostX-iOSTests */,
```
`TargetAttributes` 追加：

```
				A10000000000000000000001 = {
					CreatedOnToolsVersion = 27.0;
				};
				B20000000000000000000001 = {
					CreatedOnToolsVersion = 27.0;
				};
				C30000000000000000000001 = {
					CreatedOnToolsVersion = 27.0;
					TestTargetID = A10000000000000000000001;
				};
```
`Products` 组（`B532A667285B528900159BAC`）children 追加：

```
				A10000000000000000000002 /* GostX.app */,
				B20000000000000000000002 /* GostXTunnel.appex */,
				C30000000000000000000002 /* GostX-iOSTests.xctest */,
```
顶层组（`B532A65D285B528900159BAC`）children 追加（`Common`/`App`/`Tunnel` 已在 Task 1 加入）：

```
				A10000000000000000000010 /* iOS */,
				C3000000000000000000000A /* Tests */,
```

- [ ] **Step 6: 创建 iOS scheme**

`apple/GostX.xcodeproj/xcshareddata/xcschemes/GostX-iOS.xcscheme`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion = "2700" version = "1.7">
   <BuildAction parallelizeBuildables = "YES" buildImplicitDependencies = "YES">
      <BuildActionEntries>
         <BuildActionEntry buildForTesting = "YES" buildForRunning = "YES" buildForProfiling = "YES" buildForArchiving = "YES" buildForAnalyzing = "YES">
            <BuildableReference
               BuildableIdentifier = "primary"
               BlueprintIdentifier = "A10000000000000000000001"
               BuildableName = "GostX.app"
               BlueprintName = "GostX-iOS"
               ReferencedContainer = "container:GostX.xcodeproj">
            </BuildableReference>
         </BuildActionEntry>
      </BuildActionEntries>
   </BuildAction>
   <TestAction buildConfiguration = "Debug" selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv = "YES">
      <Testables>
         <TestableReference skipped = "NO">
            <BuildableReference
               BuildableIdentifier = "primary"
               BlueprintIdentifier = "C30000000000000000000001"
               BuildableName = "GostX-iOSTests.xctest"
               BlueprintName = "GostX-iOSTests"
               ReferencedContainer = "container:GostX.xcodeproj">
            </BuildableReference>
         </TestableReference>
      </Testables>
   </TestAction>
   <LaunchAction buildConfiguration = "Debug" selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB" launchStyle = "0" useCustomWorkingDirectory = "NO" ignoresPersistentStateOnLaunch = "NO" debugDocumentVersioning = "YES" debugServiceExtension = "internal" allowLocationSimulation = "YES">
      <BuildableProductRunnable runnableDebuggingMode = "0">
         <BuildableReference
            BuildableIdentifier = "primary"
            BlueprintIdentifier = "A10000000000000000000001"
            BuildableName = "GostX.app"
            BlueprintName = "GostX-iOS"
            ReferencedContainer = "container:GostX.xcodeproj">
         </BuildableReference>
      </BuildableProductRunnable>
   </LaunchAction>
   <ProfileAction buildConfiguration = "Release" shouldUseLaunchSchemeArgsEnv = "YES" savedToolIdentifier = "" useCustomWorkingDirectory = "NO" debugDocumentVersioning = "YES">
      <BuildableProductRunnable runnableDebuggingMode = "0">
         <BuildableReference
            BuildableIdentifier = "primary"
            BlueprintIdentifier = "A10000000000000000000001"
            BuildableName = "GostX.app"
            BlueprintName = "GostX-iOS"
            ReferencedContainer = "container:GostX.xcodeproj">
         </BuildableReference>
      </BuildableProductRunnable>
   </ProfileAction>
   <AnalyzeAction buildConfiguration = "Debug"></AnalyzeAction>
   <ArchiveAction buildConfiguration = "Release" revealArchiveInOrganizer = "YES"></ArchiveAction>
</Scheme>
```

- [ ] **Step 7: 校验工程与构建**

```bash
cd /Users/kbliu/Workspace/project/GostX
xcodebuild -list -project apple/GostX.xcodeproj
```
Expected: `Targets:` 含 6 个（`GostX-macOS`、`GostX-iOS`、`GostXTunnel-macOS`、`GostXTunnel-iOS`、`GostXTests`、`GostX-iOSTests`），`Schemes:` 含 `GostX-macOS` 与 `GostX-iOS`。

```bash
make apple-lib 2>&1 | tail -5
make ios-sim 2>&1 | tail -10
```
Expected: `** BUILD SUCCEEDED **`。模拟器启动 app 显示 `GostX` 文字。

- [ ] **Step 8: 提交**

```bash
git add -A
git commit -m "feat(apple): add iOS app, tunnel and test targets to the shared project"
```

---

### Task 5: iOS Home 页

**Files:**
- Modify: `apple/iOS/HomeView.swift`
- Create: `apple/iOS/HomeView+Button.swift`（连接按钮与状态映射）
- Create: `apple/iOS/AddProfileSheet.swift`

**Interfaces:**
- Consumes: `ConfigRepository.shared`（`profiles`、`activeProfileId`、`addProfile(name:)`、`deleteProfile(_:)`、`renameProfile(_:newName:)`、`setActiveProfile(_:)`）、`VpnManager.shared`（`status: NEVPNStatus`、`start() async throws`、`stop()`）、`ProfileNameValidator.error(name:existing:)`、`Route`
- Produces: Home 页 UI；`NEVPNStatus` 扩展属性 `isTransitioning`、`isConnected`

- [ ] **Step 1: 实现连接按钮与状态映射**

`apple/iOS/HomeView+Button.swift`：

```swift
import SwiftUI
import NetworkExtension

extension NEVPNStatus {
    var isTransitioning: Bool { self == .connecting || self == .reasserting || self == .disconnecting }
    var isConnected: Bool { self == .connected }
}

struct ConnectButton: View {
    let status: NEVPNStatus
    let action: () -> Void

    private var background: Color {
        switch status {
        case .connected: return .accentColor
        case .disconnected, .invalid: return Color.accentColor.opacity(0.2)
        default: return Color.accentColor.opacity(0.2)
        }
    }

    private var foreground: Color {
        status == .connected ? .white : .accentColor
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
```

- [ ] **Step 2: 实现新建 profile 弹窗**

`apple/iOS/AddProfileSheet.swift`：

```swift
import SwiftUI

struct AddProfileSheet: View {
    let existingNames: Set<String>
    let onConfirm: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var errorText: String?

    var body: some View {
        NavigationStack {
            Form {
                TextField("Profile name", text: $name)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                if let errorText {
                    Text(errorText).foregroundStyle(.red).font(.footnote)
                }
            }
            .navigationTitle("New Profile")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") { submit() }
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }

    private func submit() {
        if let error = ProfileNameValidator.error(name: name, existing: existingNames) {
            errorText = error
            return
        }
        onConfirm(name.trimmingCharacters(in: .whitespaces))
    }
}
```

- [ ] **Step 3: 实现 Home 页**

`apple/iOS/HomeView.swift`：

```swift
import SwiftUI
import NetworkExtension

struct HomeView: View {
    @ObservedObject private var repo = ConfigRepository.shared
    @ObservedObject private var vpn = VpnManager.shared

    @State private var path: [Route] = []
    @State private var showAddSheet = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if repo.profiles.isEmpty {
                    Text("No profiles yet. Tap + to create one.")
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                        .padding()
                } else {
                    List {
                        ForEach(repo.profiles) { profile in
                            profileRow(profile)
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("GostX")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if AppGroupConfig.loggingEnabled {
                        Button { path.append(.logs) } label: {
                            Image(systemName: "doc.text")
                        }
                    }
                    Button { showAddSheet = true } label: {
                        Image(systemName: "plus")
                    }
                    Button { path.append(.settings) } label: {
                        Image(systemName: "gearshape")
                    }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if !repo.profiles.isEmpty {
                    ConnectButton(status: vpn.status) { toggleVPN() }
                        .padding(.bottom, 12)
                }
            }
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .configEdit(let id): ConfigEditView(profileId: id)
                case .logs: LogsView()
                case .settings: SettingsView()
                case .fileManage: FileManageView()
                }
            }
        }
        .sheet(isPresented: $showAddSheet) {
            AddProfileSheet(existingNames: Set(repo.profiles.map(\.name))) { name in
                if let id = repo.addProfile(name: name) {
                    showAddSheet = false
                    path.append(.configEdit(profileId: id))
                }
            }
        }
        .alert("Error", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) {
            Button("OK") { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
        .onChange(of: vpn.status) { _, newValue in
            if newValue == .disconnected || newValue == .invalid {
                consumeTunnelError()
            }
        }
    }

    private func profileRow(_ profile: Profile) -> some View {
        HStack(spacing: 12) {
            Button {
                repo.setActiveProfile(profile.id)
            } label: {
                Image(systemName: profile.id == repo.activeProfileId
                      ? "largecircle.fill.circle" : "circle")
                    .font(.title3)
            }
            .buttonStyle(.plain)
            .disabled(vpn.status != .disconnected && vpn.status != .invalid)

            Text(profile.name)

            Spacer()

            Button {
                path.append(.configEdit(profileId: profile.id))
            } label: {
                Image(systemName: "chevron.right").foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
        }
        .contentShape(Rectangle())
    }

    private func toggleVPN() {
        guard !vpn.status.isTransitioning else { return }
        if vpn.status.isConnected {
            vpn.stop()
            return
        }
        Task {
            do {
                try await vpn.start()
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func consumeTunnelError() {
        guard let url = AppGroupConfig.containerURL?.appendingPathComponent("tunnel_error.txt"),
              let text = try? String(contentsOf: url, encoding: .utf8),
              !text.isEmpty else { return }
        try? FileManager.default.removeItem(at: url)
        errorMessage = text
    }
}
```

- [ ] **Step 4: 编译验证**

```bash
cd /Users/kbliu/Workspace/project/GostX && make ios-sim 2>&1 | tail -10
```
Expected: `** BUILD SUCCEEDED **`。

- [ ] **Step 5: 模拟器走查**

在模拟器中启动 app：可见 profile 列表（默认一个）、底部圆形按钮、顶栏三个图标；点 + 能新建；重名会提示错误；点 chevron 进入配置页（此时是空实现，仅返回空白是预期，Task 6 补齐）。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat(ios): add home screen with profile list and connect button"
```

---

### Task 6: iOS 配置编辑页

**Files:**
- Create: `apple/iOS/ConfigEditView.swift`

**Interfaces:**
- Consumes: `ConfigRepository.shared.getConfig(_:)`、`saveConfig(_:yaml:)`、`renameProfile(_:newName:)`、`deleteProfile(_:)`、`activeProfileId`；`ProfileNameValidator`
- Produces: `ConfigEditView(profileId: String)`，供 Home 的 `Route.configEdit` 使用

- [ ] **Step 1: 实现编辑页**

`apple/iOS/ConfigEditView.swift`：

```swift
import SwiftUI

struct ConfigEditView: View {
    let profileId: String

    @ObservedObject private var repo = ConfigRepository.shared
    @Environment(\.dismiss) private var dismiss

    @State private var yaml = ""
    @State private var savedYaml = ""
    @State private var showRename = false
    @State private var renameText = ""
    @State private var renameError: String?
    @State private var showDeleteConfirm = false
    @State private var showDiscardConfirm = false

    private var isDirty: Bool { yaml != savedYaml }
    private var profileName: String {
        repo.profiles.first(where: { $0.id == profileId })?.name ?? "Profile"
    }
    private var lacksTungo: Bool { !yaml.contains("tungo") }

    var body: some View {
        VStack(spacing: 0) {
            if lacksTungo {
                Text("This config has no tungo service and won't work on iOS.")
                    .font(.footnote)
                    .foregroundStyle(.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
                    .padding(.vertical, 8)
            }
            TextEditor(text: $yaml)
                .font(.system(.footnote, design: .monospaced))
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .padding(.horizontal, 12)
        }
        .navigationTitle(profileName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("Save") { save() }
                    .disabled(!isDirty)
                Menu {
                    Button("Rename…") {
                        renameText = profileName
                        renameError = nil
                        showRename = true
                    }
                    Button("Delete…", role: .destructive) { showDeleteConfirm = true }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
            ToolbarItem(placement: .topBarLeading) {
                Button {
                    if isDirty { showDiscardConfirm = true } else { dismiss() }
                } label: {
                    Image(systemName: "chevron.left")
                }
            }
        }
        .onAppear {
            yaml = repo.getConfig(profileId)
            savedYaml = yaml
        }
        .alert("Rename Profile", isPresented: $showRename) {
            TextField("Profile name", text: $renameText)
            Button("Cancel", role: .cancel) {}
            Button("Rename") { rename() }
        } message: {
            if let renameError { Text(renameError) }
        }
        .confirmationDialog("Delete this profile?", isPresented: $showDeleteConfirm, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                repo.deleteProfile(profileId)
                dismiss()
            }
        } message: {
            Text("\"\(profileName)\" will be removed. This cannot be undone.")
        }
        .confirmationDialog("Unsaved Changes", isPresented: $showDiscardConfirm, titleVisibility: .visible) {
            Button("Save") { save(); dismiss() }
            Button("Discard", role: .destructive) { dismiss() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Do you want to save the changes you made before leaving?")
        }
    }

    private func save() {
        repo.saveConfig(profileId, yaml: yaml)
        savedYaml = yaml
        if #available(iOS 17.0, *) {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }
    }

    private func rename() {
        let existing = Set(repo.profiles.filter { $0.id != profileId }.map(\.name))
        if let error = ProfileNameValidator.error(name: renameText, existing: existing) {
            renameError = error
            showRename = true
            return
        }
        _ = repo.renameProfile(profileId, newName: renameText)
        showRename = false
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd /Users/kbliu/Workspace/project/GostX && make ios-sim 2>&1 | tail -5
```
Expected: `** BUILD SUCCEEDED **`。

- [ ] **Step 3: 模拟器走查**

进入某个 profile：编辑 YAML → 返回时弹「未保存」三选；保存后重进内容保留；改名后标题变化；删除后回到 Home 且列表少一项；把 YAML 里的 `tungo` 删掉会出现橙色提示条。

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "feat(ios): add profile YAML editor with rename and delete"
```

---

### Task 7: iOS 日志页

**Files:**
- Create: `apple/iOS/LogsView.swift`

**Interfaces:**
- Consumes: `LogViewModel`（`lines`、`isFollowing`、`onAppear(loggingEnabled:)`、`onDisappear()`、`copyAll()`、`clearLog()`、`loadMoreHistory()`）、`AppGroupConfig.loggingEnabled`
- Produces: `LogsView()`，供 `Route.logs` 使用

- [ ] **Step 1: 实现日志页**

`apple/iOS/LogsView.swift`：

```swift
import SwiftUI

struct LogsView: View {
    @StateObject private var vm = LogViewModel()

    private var loggingEnabled: Bool { AppGroupConfig.loggingEnabled }

    var body: some View {
        Group {
            if !loggingEnabled {
                Text("Logging is disabled. Turn it on in Settings.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding()
            } else if vm.lines.isEmpty {
                Text("No logs")
                    .foregroundStyle(.secondary)
            } else {
                ScrollViewReader { proxy in
                    List(vm.lines.indices, id: \.self) { index in
                        Text(vm.lines[index])
                            .font(.system(.caption, design: .monospaced))
                            .listRowSeparator(.hidden)
                            .id(index)
                    }
                    .listStyle(.plain)
                    .onChange(of: vm.lines.count) { _, newCount in
                        guard vm.isFollowing, newCount > 0 else { return }
                        proxy.scrollTo(newCount - 1, anchor: .bottom)
                    }
                    .onChange(of: vm.isFollowing) { _, following in
                        guard following, !vm.lines.isEmpty else { return }
                        withAnimation {
                            proxy.scrollTo(vm.lines.count - 1, anchor: .bottom)
                        }
                    }
                }
            }
        }
        .navigationTitle("Logs")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    vm.isFollowing.toggle()
                } label: {
                    Image(systemName: vm.isFollowing ? "pause.fill" : "play.fill")
                }
                Button {
                    vm.loadMoreHistory()
                } label: {
                    Image(systemName: "arrow.up.to.line")
                }
                Button {
                    vm.copyAll()
                } label: {
                    Image(systemName: "doc.on.doc")
                }
                Button(role: .destructive) {
                    vm.clearLog()
                } label: {
                    Image(systemName: "trash")
                }
            }
        }
        .onAppear { vm.onAppear(loggingEnabled: loggingEnabled) }
        .onDisappear { vm.onDisappear() }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd /Users/kbliu/Workspace/project/GostX && make ios-sim 2>&1 | tail -5
```
Expected: `** BUILD SUCCEEDED **`。

- [ ] **Step 3: 模拟器走查**

在设置里打开日志后进入日志页：能看到历史行；点暂停/继续切换图标；点复制后用剪贴板粘贴验证；点清空后显示 `No logs`；点向上箭头加载更早历史（日志较多时才有效果）。

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "feat(ios): add log viewer with follow, copy and clear"
```

---

### Task 8: iOS 设置页

**Files:**
- Create: `apple/iOS/SettingsView.swift`

**Interfaces:**
- Consumes: `AppGroupConfig`（`loggingEnabled`、`logLevel`、`logLevelOptions`、`logMaxSizeKB`、`logMaxSizeOptions`）、`Route.fileManage`
- Produces: `SettingsView()`，供 `Route.settings` 使用；`NavigationLink` 指向 `FileManageView`

- [ ] **Step 1: 实现设置页**

`apple/iOS/SettingsView.swift`：

```swift
import SwiftUI

struct SettingsView: View {
    @State private var loggingEnabled = AppGroupConfig.loggingEnabled
    @State private var logLevel = AppGroupConfig.logLevel
    @State private var logMaxSizeKB = AppGroupConfig.logMaxSizeKB
    @State private var showLevelDialog = false
    @State private var showSizeDialog = false

    private var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
    }

    var body: some View {
        List {
            Section {
                Toggle("Logging", isOn: $loggingEnabled)
                    .onChange(of: loggingEnabled) { _, value in
                        AppGroupConfig.loggingEnabled = value
                    }
                if loggingEnabled {
                    Button {
                        showLevelDialog = true
                    } label: {
                        row("Log Level", logLevel.capitalized)
                    }
                    Button {
                        showSizeDialog = true
                    } label: {
                        row("Max Log Size", "\(logMaxSizeKB) KB")
                    }
                }
            } header: {
                Text("Logging")
            } footer: {
                Text("Restart the VPN to apply logging changes.")
            }

            Section("Files") {
                NavigationLink("File Manager") { FileManageView() }
            }

            Section {
                Text("Version \(version)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("Log Level", isPresented: $showLevelDialog, titleVisibility: .visible) {
            ForEach(AppGroupConfig.logLevelOptions, id: \.self) { level in
                Button(level.capitalized) {
                    logLevel = level
                    AppGroupConfig.logLevel = level
                }
            }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("Max Log Size", isPresented: $showSizeDialog, titleVisibility: .visible) {
            ForEach(AppGroupConfig.logMaxSizeOptions, id: \.self) { size in
                Button("\(size) KB") {
                    logMaxSizeKB = size
                    AppGroupConfig.logMaxSizeKB = size
                }
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    private func row(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title).foregroundStyle(.primary)
            Spacer()
            Text(value).foregroundStyle(.secondary)
            Image(systemName: "chevron.right")
                .font(.footnote)
                .foregroundStyle(.tertiary)
        }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd /Users/kbliu/Workspace/project/GostX && make ios-sim 2>&1 | tail -5
```
Expected: `** BUILD SUCCEEDED **`。

- [ ] **Step 3: 模拟器走查**

关闭日志开关时「Log Level」「Max Log Size」两行消失；重新打开后出现；选择 `debug` 与 `512 KB` 后重进设置页仍保持所选值；底部显示版本号。

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "feat(ios): add settings screen with logging controls"
```

---

### Task 9: iOS 文件管理与文件编辑页

**Files:**
- Create: `apple/iOS/FileManageView.swift`
- Create: `apple/iOS/FileEditorView.swift`

**Interfaces:**
- Consumes: `FileManageViewModel`（`files`、`alertMessage`、`pendingOverwrite`、`refresh()`、`createFile(_:)`、`importFile(from:)`、`confirmOverwrite()`、`cancelOverwrite()`、`renameFile(_:to:)`、`deleteFile(_:)`、`exportFile(_:to:)`、`filePath(_:)`）
- Produces: `FileManageView()`、`FileEditorView(fileName: String)`

- [ ] **Step 1: 实现文件列表页**

`apple/iOS/FileManageView.swift`：

```swift
import SwiftUI
import UniformTypeIdentifiers

struct FileManageView: View {
    @StateObject private var vm = FileManageViewModel()
    @State private var showImporter = false
    @State private var renameTarget: String?
    @State private var renameText = ""
    @State private var deleteTarget: String?

    var body: some View {
        Group {
            if vm.files.isEmpty {
                Text("No files")
                    .foregroundStyle(.secondary)
            } else {
                List(vm.files) { file in
                    NavigationLink {
                        FileEditorView(fileName: file.name)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(file.name)
                            Text("\(file.sizeBytes) B")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button(role: .destructive) {
                            deleteTarget = file.name
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                        Button {
                            renameTarget = file.name
                            renameText = file.name
                        } label: {
                            Label("Rename", systemImage: "pencil")
                        }
                    }
                }
            }
        }
        .navigationTitle("Files")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showImporter = true
                } label: {
                    Image(systemName: "square.and.arrow.down")
                }
            }
        }
        .fileImporter(isPresented: $showImporter,
                      allowedContentTypes: [.data, .plainText, .text]) { result in
            if case .success(let url) = result {
                vm.importFile(from: url)
            }
        }
        .onAppear { vm.refresh() }
        .alert("Error", isPresented: Binding(
            get: { vm.alertMessage != nil },
            set: { if !$0 { vm.alertMessage = nil } }
        )) {
            Button("OK") { vm.alertMessage = nil }
        } message: {
            Text(vm.alertMessage ?? "")
        }
        .alert("Rename File", isPresented: Binding(
            get: { renameTarget != nil },
            set: { if !$0 { renameTarget = nil } }
        )) {
            TextField("File name", text: $renameText)
            Button("Cancel", role: .cancel) { renameTarget = nil }
            Button("Rename") {
                if let target = renameTarget {
                    vm.renameFile(target, to: renameText)
                }
                renameTarget = nil
            }
        }
        .confirmationDialog("Delete File", isPresented: Binding(
            get: { deleteTarget != nil },
            set: { if !$0 { deleteTarget = nil } }
        ), titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                if let target = deleteTarget {
                    vm.deleteFile(target)
                }
                deleteTarget = nil
            }
            Button("Cancel", role: .cancel) { deleteTarget = nil }
        } message: {
            Text("Are you sure you want to delete \"\(deleteTarget ?? "")\"? This cannot be undone.")
        }
        .confirmationDialog("File Already Exists", isPresented: Binding(
            get: { vm.pendingOverwrite != nil },
            set: { if !$0 { vm.cancelOverwrite() } }
        ), titleVisibility: .visible) {
            Button("Replace", role: .destructive) { vm.confirmOverwrite() }
            Button("Cancel", role: .cancel) { vm.cancelOverwrite() }
        } message: {
            Text("A file named \"\(vm.pendingOverwrite?.fileName ?? "")\" already exists. Replace it?")
        }
    }
}
```

- [ ] **Step 2: 实现文件编辑页（含导出）**

`apple/iOS/FileEditorView.swift`：

```swift
import SwiftUI

struct FileEditorView: View {
    let fileName: String

    @StateObject private var vm = FileManageViewModel()
    @State private var text = ""
    @State private var savedText = ""

    private var isDirty: Bool { text != savedText }

    var body: some View {
        TextEditor(text: $text)
            .font(.system(.footnote, design: .monospaced))
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
            .padding(.horizontal, 12)
            .navigationTitle(fileName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button("Save") { save() }
                        .disabled(!isDirty)
                    ShareLink(item: URL(fileURLWithPath: vm.filePath(fileName)))
                }
            }
            .onAppear {
                vm.selectFile(fileName)
                text = vm.fileContent
                savedText = text
            }
    }

    private func save() {
        vm.fileContent = text
        vm.saveFileContent()
        savedText = text
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
cd /Users/kbliu/Workspace/project/GostX && make ios-sim 2>&1 | tail -5
```
Expected: `** BUILD SUCCEEDED **`。

- [ ] **Step 4: 模拟器走查**

从设置进入文件管理：点导入按钮可从「文件」App 选一个文本文件；列表显示文件名与大小；左滑可重命名/删除（删除有确认）；点文件名进入编辑页，改动后 Save 生效；ShareLink 能弹出系统分享面板；导入同名文件时弹「是否替换」。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(ios): add file manager and file editor screens"
```

---

### Task 10: iOS 多语言（String Catalog）

**Files:**
- Create: `apple/iOS/Localizable.xcstrings`

**Interfaces:**
- Consumes: Task 5–9 视图中作为字面量出现的英文文案
- Produces: `apple/iOS/Localizable.xcstrings`（源语言 `en`，`zh-Hans` 译文），Xcode 自动加入 `GostX-iOS` target 的资源

- [ ] **Step 1: 创建 String Catalog**

`apple/iOS/Localizable.xcstrings`（`%@` 为带参格式，使用 `stringUnit` 的 `variations` 不需要，直接按含 `%@` 的原文作为 key）：

```json
{
  "sourceLanguage" : "en",
  "strings" : {
    "A file named \"%@\" already exists. Replace it?" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "已存在名为“%@”的文件，是否替换？" } } }
    },
    "Are you sure you want to delete \"%@\"? This cannot be undone." : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "确定删除“%@”吗？此操作无法撤销。" } } }
    },
    "Cancel" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "取消" } } }
    },
    "Create" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "创建" } } }
    },
    "Delete" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "删除" } } }
    },
    "Delete File" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "删除文件" } } }
    },
    "Delete this profile?" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "删除该配置文件？" } } }
    },
    "Delete…" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "删除…" } } }
    },
    "Discard" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "放弃" } } }
    },
    "Do you want to save the changes you made before leaving?" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "离开前保存修改吗？" } } }
    },
    "Error" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "错误" } } }
    },
    "File Already Exists" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "文件已存在" } } }
    },
    "File Manager" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "文件管理" } } }
    },
    "File name" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "文件名" } } }
    },
    "Files" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "文件" } } }
    },
    "Log Level" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "日志级别" } } }
    },
    "Logging" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "日志" } } }
    },
    "Logging is disabled. Turn it on in Settings." : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "日志已关闭，可在设置中开启。" } } }
    },
    "Logs" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "日志" } } }
    },
    "Max Log Size" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "日志上限" } } }
    },
    "New Profile" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "新建配置" } } }
    },
    "No files" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "暂无文件" } } }
    },
    "No logs" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "暂无日志" } } }
    },
    "No profiles yet. Tap + to create one." : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "还没有配置，点击 + 新建。" } } }
    },
    "OK" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "确定" } } }
    },
    "Profile name" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "配置名称" } } }
    },
    "Rename" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "重命名" } } }
    },
    "Rename File" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "重命名文件" } } }
    },
    "Rename Profile" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "重命名配置" } } }
    },
    "Rename…" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "重命名…" } } }
    },
    "Replace" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "替换" } } }
    },
    "Restart the VPN to apply logging changes." : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "重启 VPN 后生效。" } } }
    },
    "Save" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "保存" } } }
    },
    "Settings" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "设置" } } }
    },
    "This config has no tungo service and can't be used on iOS." : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "该配置没有 tungo 服务，无法在 iOS 上使用。" } } }
    },
    "This config has no tungo service and won't work on iOS." : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "该配置没有 tungo 服务，在 iOS 上无法工作。" } } }
    },
    "Unsaved Changes" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "未保存的修改" } } }
    },
    "Version %@" : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "版本 %@" } } }
    },
    "Profile name can't be empty." : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "配置名称不能为空。" } } }
    },
    "Profile name can't contain a comma." : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "配置名称不能包含逗号。" } } }
    },
    "A profile with that name already exists." : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "已存在同名配置。" } } }
    },
    "\"%@\" will be removed. This cannot be undone." : {
      "localizations" : { "zh-Hans" : { "stringUnit" : { "state" : "translated", "value" : "“%@”将被删除，此操作无法撤销。" } } }
    }
  },
  "version" : "1.0"
}
```

- [ ] **Step 2: 用带参格式替换字面量**

把 `apple/iOS/ConfigEditView.swift` 中

```swift
            Text("\"\(profileName)\" will be removed. This cannot be undone.")
```
改为

```swift
            Text(String(format: NSLocalizedString("\"%@\" will be removed. This cannot be undone.", comment: ""), profileName))
```

把 `apple/iOS/SettingsView.swift` 中

```swift
                Text("Version \(version)")
```
改为

```swift
                Text(String(format: NSLocalizedString("Version %@", comment: ""), version))
```

把 `apple/iOS/FileManageView.swift` 中两处带参文案改为 `String(format: NSLocalizedString(..., comment: ""), name)` 形式（`A file named "%@" already exists. Replace it?` 与 `Are you sure you want to delete "%@"? This cannot be undone.`）。

- [ ] **Step 3: 编译并验证中英文切换**

```bash
cd /Users/kbliu/Workspace/project/GostX && make ios-sim 2>&1 | tail -5
```
Expected: `** BUILD SUCCEEDED **`。

模拟器：设置 → 通用 → 语言与地区切换为「简体中文」后重启 app，确认顶栏标题、按钮、弹窗文案均为中文；切回英文恢复原文。

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "feat(ios): localize strings with a string catalog"
```

---

### Task 11: iOS 扩展接线、内存上限与发版链路

**Files:**
- Modify: `apple/Shared/Tunnel/PacketTunnelProvider.swift`
- Modify: `apple/Shared/App/VpnManager.swift`
- Modify: `Makefile`（若 Task 1 尚未包含 `ios` / `ios-release`）

**Interfaces:**
- Consumes: `LibgostSetMemoryLimitBytes(_:)`（Task 2 产物）、`AppGroupConfig.containerURL`
- Produces: iOS 扩展可用的启动/停止流程；`make ios`、`make ios-release`

- [ ] **Step 1: 扩展内设置 iOS 内存上限与错误回传**

在 `apple/Shared/Tunnel/PacketTunnelProvider.swift` 的 `startTunnel` 起始处（`setWorkDirAndLog()` 之前）插入：

```swift
        #if os(iOS)
        // Network Extension 常驻内存上限约 50 MB，收紧 Go 堆上限避免被系统强杀。
        LibgostSetMemoryLimitBytes(30 * 1024 * 1024)
        #endif
```

新增私有方法，并在所有 `cancelTunnelWithError(...)` 之前调用（5 处：配置缺失、StartGost 失败、setTunnelNetworkSettings 失败、fd 缺失、StartTun 失败）：

```swift
    /// 把失败原因写进 App Group，供 App 在状态变为 disconnected 时展示。
    private func recordError(_ message: String) {
        guard let url = AppGroupConfig.containerURL?.appendingPathComponent("tunnel_error.txt") else {
            return
        }
        try? message.write(to: url, atomically: true, encoding: .utf8)
    }
```

调用示例（替换原有 `cancelTunnelWithError` 调用前的错误分支）：

```swift
            recordError(error.localizedDescription)
            cancelTunnelWithError(error as NSError)
```

- [ ] **Step 2: 连接前同步 YAML 到 App Group**

在 `apple/Shared/App/VpnManager.swift` 的 `start()` 开头插入：

```swift
        // 扩展只读 App Group；连接前必须把当前生效配置落盘，否则读到旧配置。
        AppGroupConfig.writeYaml(ConfigRepository.shared.activeConfig)
```

- [ ] **Step 3: 配置校验：拒绝无 tungo 的配置**

在 `apple/Shared/App/VpnManager.swift` 的 `start()` 中、写入 YAML 之前插入：

```swift
        let yaml = ConfigRepository.shared.activeConfig
        guard yaml.contains("tungo") else {
            throw NSError(
                domain: "GostX",
                code: 10,
                userInfo: [NSLocalizedDescriptionKey:
                    NSLocalizedString("This config has no tungo service and can't be used on iOS.", comment: "")]
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
```
把 Step 2 的写入改为 `AppGroupConfig.writeYaml(yaml)`，并在文件顶部 `import Libgost`。

- [ ] **Step 4: 编译并真机安装**

```bash
cd /Users/kbliu/Workspace/project/GostX
make apple-lib 2>&1 | tail -3
make ios 2>&1 | tail -10
```
Expected: `** BUILD SUCCEEDED **`。用 Xcode 或 `xcrun devicectl device install app` 装到真机。

- [ ] **Step 5: 真机验收（必须逐条记录结果）**

1. 首次连接弹「允许 VPN 配置」，连接成功后按钮变为实心态；
2. Safari 打开外网页面成功；日志里能看到 TUN 与 DNS 相关记录；
3. 配置里加 `handler.metadata.routes: 192.168.2.22/32` 后，该地址能通过隧道访问；
4. 断开→重连成功；切换 Wi-Fi/蜂窝后隧道仍可用；
5. 把 YAML 改成无 `tungo`：连接被拒并弹出「This config has no tungo service…」；
6. 用错误的 `chain` 节点地址：连接失败并弹出 `tunnel_error.txt` 中的具体原因；
7. 日志页 tail/跟随/复制/清空正常；设置改级别与上限后重连生效；
8. Xcode 调试挂到 `GostXTunnel-iOS` 扩展进程，跑 10 分钟视频流量，RSS 峰值 < 50 MB。

若第 8 条超限，按顺序尝试：把上限降到 24 MB、日志级别默认 `info`、删除日志跟随的高频写盘；仍超限则停止实施并上报。

- [ ] **Step 6: 归档并检查切片**

```bash
cd /Users/kbliu/Workspace/project/GostX
make ios-release 2>&1 | tail -5
lipo -archs apple/build/Release-ios/GostX.app/Frameworks/Libgost.xcframework/ios-arm64/Libgost.framework/Libgost
```
Expected: 归档成功；`lipo` 输出 `arm64`（不包含 `x86_64`，说明模拟器切片未进包）。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "feat(ios): wire tunnel memory cap, error reporting and release target"
```

---

## 后续步骤（不在本计划范围）

- App Store Connect 建 app 记录、勾选两个 App ID 的 Network Extensions 能力、上传 TestFlight 构建（人工操作）。
- macOS 若要上架 Mac App Store，在同一 app 记录里添加 macOS 平台（universal purchase）。