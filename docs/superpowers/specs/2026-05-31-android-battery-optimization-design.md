# Android 后台省电优化 + 外部存储文件访问 设计文档

日期：2026-05-31  
分支：multi-config  
作者：Copilot

---

## 背景

GostX 在 Android 后台运行时耗电偏高。通过代码审查与 sing-box 参考实现对比，发现以下主要耗电源头：

1. VPN 服务每秒无条件轮询 Go 日志缓冲（不感知屏幕状态）
2. 日志页面 ViewModel 每秒轮询日志文件（不感知页面是否可见）
3. 没有 Doze 模式处理（设备深度睡眠时仍有定时唤醒）
4. Go 运行时没有内存上限（连接量大时 heap 膨胀，GC 频繁唤醒 CPU）

此外增加一个功能需求：让 gost 配置可以用相对路径引用外部存储中的 bypass 文件。

---

## 方案选择

**方案 A（采用）：最小侵入性修补**  
在现有轮询逻辑上加屏幕感知、Doze 感知，新增 Go 内存限制函数和工作目录函数，不改 JNI 接口结构，不动 VPN 核心路由逻辑。

**方案 B（未采用）：Push 化日志**  
Go 侧改为主动 JNI callback，彻底去掉周期轮询。改动较大，留作未来优化。

**方案 C（未采用）：WorkManager 调度**  
最小唤醒间隔 15 分钟，不适合实时日志场景。

---

## 改动清单

| # | 改动 | 涉及文件 | 层 |
|---|------|----------|----|
| 1 | 屏幕感知 VPN 日志轮询 | `GostVpnService.kt` | Android |
| 2 | 日志页面生命周期驱动 UI 轮询 | `LogViewModel.kt`, `LogScreen.kt` | Android |
| 3 | Doze 模式感知 | `GostVpnService.kt` | Android |
| 4 | Go 运行时内存限制 | `go/gostlib/gostlib.go`, `GostVpnService.kt` | Go + Android |
| 5 | 工作目录设置（外部存储相对路径） | `go/gostlib/gostlib.go`, `GostVpnService.kt` | Go + Android |

---

## 详细设计

### 改动 1 — 屏幕感知 VPN 日志轮询

**文件**：`android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt`

**现状**：`startVpnLogPolling()` 在 VPN 启动后每秒调用 `GostLibBridge.getVPNLog()`，即使屏幕已关闭。

**修改**：
- `GostVpnService` 目前没有 BroadcastReceiver（只有 NetworkCallback）。新增一个 `serviceReceiver`，在 `startVpn()` 末尾注册，`stopVpn()` 时注销（改动 1 和改动 3 共用同一个 receiver，分别注册不同 action）
- `serviceReceiver` 监听 `Intent.ACTION_SCREEN_ON` / `Intent.ACTION_SCREEN_OFF`
- 收到 `SCREEN_OFF`：取消 `vpnLogJob`
- 收到 `SCREEN_ON`：若 VPN 处于 `CONNECTED` 状态，重启 `vpnLogJob`

**注意**：Go 侧 `vpnLogCh` channel 容量 512，屏幕关闭期间最多丢弃超额日志，后台运行时可接受。

---

### 改动 2 — 日志页面生命周期驱动 UI 轮询

**文件**：
- `android/app/src/main/kotlin/cn/liukebin/GostX/ui/log/LogViewModel.kt`
- `android/app/src/main/kotlin/cn/liukebin/GostX/ui/log/LogScreen.kt`

**现状**：`loadInitial()` 调用后自动启动轮询，即使用户离开日志页面 ViewModel 仍在轮询。

**修改**：
- `LogViewModel`：将 `startPolling()` 改为 `public`，新增 `public fun stopPolling()`（内部取消 `pollJob`）；`loadInitial()` 只做初始读取，不自动启动轮询
- `LogScreen.kt`：使用 `DisposableEffect(Unit)` — 进入 composable 时调 `viewModel.startPolling()`，`onDispose` 时调 `viewModel.stopPolling()`

---

### 改动 3 — Doze 模式感知

**文件**：`android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt`

**现状**：GostX 完全不感知 Android Doze 模式，Doze 期间轮询协程仍在运行。

**修改**：
- 在 `startVpn()` 注册的 `serviceReceiver` 中（与改动 1 共用）追加 `PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED`（API 23+，低版本跳过）
- **进入 Doze**（`isDeviceIdleMode == true`）：
  - 取消 `vpnLogJob`
  - 取消注册网络回调（`unregisterNetworkCallback()`），避免 Doze 期间虚假网络切换触发不必要的 VPN 重连
- **退出 Doze**（`isDeviceIdleMode == false`）：
  - 重新注册网络回调（`registerNetworkCallback()`）
  - 若 VPN 仍为 `CONNECTED` 状态，重启 `vpnLogJob`
- VPN 连接本身不断开——Android Doze 期间 VPN fd 保持有效

---

### 改动 4 — Go 运行时内存限制

**文件**：
- `go/gostlib/gostlib.go`（新增函数）
- `android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt`

**现状**：Go 运行时使用默认 GC 策略（`GOGC=100`），堆无限增长，连接量大时 GC 停顿频繁。

**Go 侧新增函数**（参考 sing-box `experimental/libbox/memory.go`）：

```go
// SetMemoryLimit 为 Go 运行时设置内存上限，在 VPN 启动时开启，停止时关闭。
// enabled=true: 激进 GC（GOGC=10）+ 30MB 软上限，抑制 heap 膨胀。
// enabled=false: 恢复默认，避免影响非 VPN 模式下的普通服务。
func SetMemoryLimit(enabled bool) {
    const limit = 30 * 1024 * 1024
    if enabled {
        debug.SetGCPercent(10)
        debug.SetMemoryLimit(limit)
    } else {
        debug.SetGCPercent(100)
        debug.SetMemoryLimit(math.MaxInt64)
    }
}
```

**Android 侧**：
- `GostVpnService.startVpn()` 成功后调用 `GostLibBridge.setMemoryLimit(true)`
- `GostVpnService.stopVpn()` 中调用 `GostLibBridge.setMemoryLimit(false)`
- `GostLibBridge` 新增 `setMemoryLimit(enabled: Boolean)` 反射调用

---

### 改动 5 — 工作目录设置（外部存储相对路径支持）

**文件**：
- `go/gostlib/gostlib.go`（新增函数）
- `android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt`

**背景**：
- gost 的 `bypass.file.path`、`hosts.file.path` 等配置项通过 `os.ReadFile(filename)` 读取文件
- Go 进程在 Android 中的工作目录默认为 `/`，相对路径无实际意义
- 用户希望将大型 IP 列表放在外部存储（`getExternalFilesDir(null)`）并在 gost 配置中直接引用文件名

**Go 侧新增函数**：

```go
// SetWorkDir 设置 Go 进程的工作目录，使 gost 配置中的相对文件路径
// 相对于指定目录解析。应在 Start/StartVPNMode 之前调用。
func SetWorkDir(path string) error {
    return os.Chdir(path)
}
```

**Android 侧**：在 `GostVpnService.onCreate()` 中调用：

```kotlin
val externalDir = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
GostLibBridge.setWorkDir(externalDir)
```

**用户使用方式**：将文件放入 `/storage/emulated/0/Android/data/cn.liukebin.GostX/files/`，配置中写：

```yaml
bypasses:
  - name: china
    file:
      path: china_ip_list.txt   # 相对于外部存储目录
```

**注意**：
- `os.Chdir` 影响整个进程，只需在 `onCreate` 调用一次
- 外部存储不可用时自动回退到内部存储 `filesDir`
- 不需要额外 Android 权限（app 专属外部目录，Android 4.4+ 免权限）

---

## 测试策略

| 改动 | 单元测试 | 手动验证 |
|------|----------|----------|
| 1 屏幕感知轮询 | 用 mock receiver 验证 `vpnLogJob` cancel/restart | `adb shell input keyevent KEYCODE_POWER` 后查 logcat |
| 2 UI 轮询生命周期 | `TestCoroutineDispatcher` 验证 `startPolling/stopPolling` | 打开/离开日志页，查 logcat 轮询协程是否启停 |
| 3 Doze 模式 | 无（依赖系统广播） | `adb shell dumpsys deviceidle force-idle` / `unforce` |
| 4 内存限制 | 无（调用标准库） | Android Studio Profiler native heap 曲线对比 |
| 5 工作目录 | `gostlib_test.go` 验证 `SetWorkDir` 后 `os.Getwd()` | 放 bypass 文件，写相对路径配置，验证 gost 能加载 |

---

## 不在范围内

- Push 化日志（方案 B）：彻底去掉轮询，需改 JNI 接口，留作未来优化
- 连接数上限：当前 gVisor handler 无连接总数限制，高流量场景可后续评估
- WakeLock：VPN 场景 Android 系统已保持必要的唤醒，不需要应用层额外申请
