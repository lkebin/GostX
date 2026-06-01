# Logging Toggle Design

**Date:** 2026-06-01  
**Branch:** multi-config

## Problem

当前日志系统始终写入磁盘，在活跃浏览期间产生不必要的 I/O 和 Go 层字符串分配。大部分用户日常不需要查看日志，默认关闭可降低电池消耗。

## Goal

- 新增「日志记录」开关，默认关闭
- 关闭时 Go 层和 Kotlin 层均零日志开销
- 开关在设置页面控制；日志入口仅在开关打开时显示
- 更改开关后需重启 VPN 生效

## Architecture

### 数据层

`ConfigRepository` 新增：
- `loggingEnabled: Boolean` — 读写 `SharedPreferences` key `"logging_enabled"`，默认 `false`
- `loggingEnabledFlow: StateFlow<Boolean>` — 供 UI 订阅

### Go 层（`go/gostlib/vpnlog.go`）

新增：
```go
var loggingEnabled atomic.Bool  // default false

func SetLoggingEnabled(v bool) { loggingEnabled.Store(v) }
```

`logVPN()` 修改：
```go
func logVPN(format string, args ...any) {
    if !loggingEnabled.Load() { return }
    // ... 现有逻辑
}
```

效果：关闭时跳过 `fmt.Sprintf` 字符串分配和 channel 写入，goroutine 不启动。

### VPN 服务层（`GostVpnService.kt`）

`startVpn()` 里按设置调用：
```kotlin
val loggingOn = configRepo.loggingEnabled
GostLibBridge.setLoggingEnabled(loggingOn)
if (loggingOn) GostLibBridge.setLogFile(LogRepository.getLogFile().absolutePath)
```

### UI 层

**SettingsScreen（新增）**
- TopAppBar + 返回按钮
- 「日志记录」`Switch` 绑定 `ConfigRepository.loggingEnabled`
- 提示文字：「关闭后需重启 VPN 生效」

**HomeScreen（修改）**
- TopAppBar 原有「日志」图标替换为「设置」图标 → 导航到 SettingsScreen
- 在 SettingsScreen 里，从设置页跳到日志页
- 或：TopAppBar 保留「日志」图标但仅在 `loggingEnabled == true` 时显示；同时新增「设置」图标

> 采用方案：TopAppBar 同时显示设置图标；日志图标仅当 `loggingEnabled` 为 true 时显示。

**导航（`Screen.kt` + `MainActivity.kt`）**
- 新增 `Screen.Settings` 路由
- 在 `NavHost` 中注册 `SettingsScreen`

## Data Flow

```
用户在 SettingsScreen 切换开关
        │
        ▼
ConfigRepository.setLoggingEnabled(bool)
        │  (写 SharedPreferences)
        │
        ▼
loggingEnabledFlow 更新 → HomeScreen 订阅 → 日志图标显示/隐藏
        │
        ▼
下次 VPN 启动时：
  GostVpnService.startVpn()
    ├── GostLibBridge.setLoggingEnabled(flag)  ← Go 原子标志
    └── if (flag) GostLibBridge.setLogFile(path)
```

## Go AAR Rebuild

`SetLoggingEnabled` 是新导出函数，需要重建 AAR：
```bash
cd go && make gostlib.aar
git add -f android/app/libs/gostlib.aar android/app/libs/gostlib-sources.jar
```

## Testing

- `GostLibBridge` 调用通过反射 — 现有 `HomeViewModelTest` 不受影响
- Go 单元测试：`TestSetLoggingEnabled` — 验证关闭时 `logVPN` 不写 channel
- `LogViewModelLogicTest` — 现有测试继续有效（不涉及 loggingEnabled）

## Out of Scope

- 日志自动轮转 / 保留天数
- 不同日志级别（verbose/debug/info）
- 运行时动态切换（无需重启 VPN）
