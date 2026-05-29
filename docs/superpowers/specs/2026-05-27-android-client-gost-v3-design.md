# GostX Android Client & Gost v3 Upgrade — Design Spec

**Date:** 2026-05-27  
**Status:** Approved

---

## Overview

This document describes:
1. Upgrading the Go library from gost v2 to gost v3
2. Adding an Android client with full VPN support via tun2socks
3. Restructuring the repository for multi-platform support
4. Updating the macOS app to use YAML configuration (gost v3 format)

**Out of scope:** iOS client (architecture is designed to be extensible to iOS with minimal changes).  
**Removed:** Tor support (dependency removed to simplify the build).

---

## Repository Structure

```
GostX/
├── go/                          # Go shared library module
│   ├── go.mod                   # Upgraded to gost v3 (go-gost/x)
│   ├── gostlib/                 # Shared platform-agnostic core package
│   │   ├── gostlib.go           # Start/Stop/Status API (gomobile-compatible)
│   │   └── tun.go               # tun2socks-go VPN integration
│   ├── cmd/
│   │   └── macos/
│   │       └── main.go          # cgo exports → libgost.a (macOS)
│   └── Makefile                 # Targets: libgost.a (macOS), gostlib.aar (Android)
│                                # gomobile bind targets ./gostlib directly
│
├── android/                     # Android project (Kotlin + Jetpack Compose)
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── kotlin/com/gostx/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── ui/
│   │   │   │   │   ├── HomeScreen.kt       # Status + Start/Stop + traffic stats
│   │   │   │   │   ├── LogScreen.kt        # Real-time log viewer
│   │   │   │   │   └── ConfigScreen.kt     # YAML editor + profile management
│   │   │   │   ├── service/
│   │   │   │   │   └── GostVpnService.kt   # Android VpnService
│   │   │   │   ├── tile/
│   │   │   │   │   └── GostTileService.kt  # Quick Settings tile
│   │   │   │   ├── data/
│   │   │   │   │   ├── ConfigRepository.kt # YAML config + profile persistence
│   │   │   │   │   └── VpnStateRepository.kt # StateFlow-based VPN state
│   │   │   │   └── notification/
│   │   │   │       └── NotificationHelper.kt
│   │   │   └── res/
│   │   ├── libs/
│   │   │   └── gostlib.aar      # Built from go/cmd/android via gomobile
│   │   └── build.gradle
│   └── build.gradle
│
├── macos/                       # macOS project (renamed from GostX/)
│   ├── GostX.xcodeproj
│   ├── AppDelegate.swift
│   ├── SettingsView.swift       # Updated: YAML editor
│   ├── MacExtrasConfigurator.swift
│   ├── Arguments.swift          # Removed (replaced by YAML config)
│   └── Assets.xcassets / Info.plist / etc.
│
└── Makefile                     # Root: delegates to go/ and android/
```

---

## Section 1: Go Shared Library (gostlib)

### Dependencies

**Added:**
```
github.com/go-gost/x            # gost v3 main package (handlers, listeners, config)
github.com/go-gost/core         # gost v3 core interfaces
github.com/xjasonlyu/tun2socks/v2  # tun2socks (pure Go)
```

**Removed:**
```
github.com/ginuerzh/gost        # gost v2
github.com/cretz/bine           # Tor
git.torproject.org/...          # Tor static libs
```

### go/gostlib/gostlib.go

Exposes a gomobile-compatible API (only basic Go types):

```go
package gostlib

// Start starts gost v3 with the given YAML configuration string.
// An internal SOCKS5 listener on 127.0.0.1:10808 is automatically
// injected for use by tun2socks (VPN mode).
func Start(yamlConfig string) error

// Stop stops all running gost services.
func Stop() error

// IsRunning returns true if gost is currently running.
func IsRunning() bool

// GetStatus returns a JSON string containing listen addresses and run state.
func GetStatus() string
```

### go/gostlib/tun.go

VPN layer using tun2socks-go. No platform build tags — pure Go, works on Android and iOS.

```go
package gostlib

// StartVPN starts tun2socks, routing all device traffic through gost.
// fd is the TUN file descriptor from Android VpnService or iOS NEPacketTunnelProvider.
// mtu is typically 1500.
func StartVPN(fd int, mtu int) error

// StopVPN stops tun2socks.
func StopVPN() error
```

### Traffic Flow (VPN Mode)

```
Device network traffic
  → Android TUN interface (fd from VpnService.Builder.establish())
  → tun2socks-go  (device: "fd://N", proxy: "socks5://127.0.0.1:10808")
  → gost internal SOCKS5 listener (auto-injected at 127.0.0.1:10808)
  → user-configured outbound chain (proxy servers, forwarding rules, etc.)
  → destination server
```

The internal SOCKS5 port `10808` is always injected by `Start()` when called in VPN mode. Users write outbound configuration only; they do not need to declare the local listener.

### go/cmd/macos/main.go

Wraps gostlib in cgo exports for the macOS c-archive:

```go
//export gostRunYaml
func gostRunYaml(yaml *C.char, fd *C.long) int

//export gostStop
func gostStop() int

//export gostInfo
func gostInfo() *C.struct_info
```

### Build Targets

```makefile
# go/Makefile

libgost.a:      # macOS c-archive (arm64 + amd64 universal binary)
    CGO_ENABLED=1 GOARCH=arm64 go build -buildmode=c-archive ./cmd/macos
    CGO_ENABLED=1 GOARCH=amd64 go build -buildmode=c-archive ./cmd/macos
    lipo libgost_arm64.a libgost_amd64.a -create -output libgost.a

gostlib.aar:    # Android .aar via gomobile bind (targets gostlib package directly)
    gomobile bind -target android/arm,android/arm64,android/amd64 -o gostlib.aar ./gostlib
    cp gostlib.aar ../android/app/libs/
```

### iOS Extensibility

When iOS support is added, only the following is needed — `gostlib` itself is unchanged:
```bash
gomobile bind -target ios -o GostLib.xcframework ./gostlib
```
The `StartVPN(fd, mtu)` API accepts the TUN fd from iOS `NEPacketTunnelProvider` directly.

---

## Section 2: Android Application

### Tech Stack
- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Navigation:** Compose Navigation (`NavController`)
- **State:** `StateFlow` + `ViewModel`
- **Persistence:** `SharedPreferences` (YAML config string, active profile ID)
- **Minimum SDK:** API 26 (Android 8.0); targets API 34+

### Navigation Structure

```
MainActivity
└── NavHost
    ├── HomeScreen     (start destination)
    ├── LogScreen      (from TopAppBar 📋 button)
    └── ConfigScreen   (from TopAppBar ⚙️ button)
```

```
┌─────────────────────────────┐
│  GostX          📋    ⚙️    │  ← TopAppBar
└─────────────────────────────┘
```

No bottom navigation bar. The app has three screens accessed via the top bar.

### HomeScreen

Displays VPN state, connection stats, and the primary Start/Stop action.

```
┌─────────────────────────────┐
│  GostX          📋    ⚙️    │
│─────────────────────────────│
│                             │
│  ●  运行中   已连接 2m30s   │  ← status indicator (green/grey dot)
│  监听: 127.0.0.1:10808      │  ← from gostlib.GetStatus()
│                             │
│  ↑ 1.2 MB/s   ↓ 3.4 MB/s   │  ← live traffic stats from tun2socks
│                             │
│  ┌──────────────────────┐   │
│  │     启动 / 停止 VPN   │   │  ← primary action button
│  └──────────────────────┘   │
│                             │
└─────────────────────────────┘
```

### LogScreen

Real-time log output from gost. Accessible via 📋 in TopAppBar.

- Auto-scrolls to latest entry
- "清空" button clears in-memory log buffer
- "复制" button copies full log to clipboard
- Log entries sourced from `gostlib` via a callback/channel

### ConfigScreen

YAML configuration editor with profile management.

- Multi-line monospace text field (full screen)
- Profile Chips at top: user can save and switch between named configurations
- **Validate** button: parses YAML and shows inline error (line number + message) before saving
- "保存" in TopAppBar saves current text to active profile
- Built-in default template shown on first launch
- Import/export via Android share sheet

```
┌─────────────────────────────┐
│  ←  配置                保存 │
│  [家用] [公司] [+]           │  ← profile chips
│  ┌───────────────────────┐  │
│  │ services:             │  │
│  │   - name: socks5      │  │  ← monospace TextField
│  │     addr: :1080       │  │
│  │     ...               │  │
│  └───────────────────────┘  │
│  ⚠ 第3行: 缺少 handler 字段  │  ← validation error (if any)
│  [验证]  [默认模板]  [分享]   │
└─────────────────────────────┘
```

### GostVpnService

```kotlin
class GostVpnService : VpnService() {
    fun start(yamlConfig: String) {
        // 1. VpnService.Builder: set MTU, DNS, routes (0.0.0.0/0 for full tunnel)
        // 2. builder.establish() → ParcelFileDescriptor (TUN fd)
        // 3. protect() the socket gost will use (prevents routing loop)
        // 4. gostlib.Gostlib.start(yamlConfig)
        // 5. gostlib.Gostlib.startVPN(fd, mtu=1500)
        // 6. startForeground() with persistent notification
    }

    fun stop() {
        // gostlib.Gostlib.stopVPN()
        // gostlib.Gostlib.stop()
        // stopForeground() + close TUN fd
    }
}
```

**Network change handling:** Registers a `ConnectivityManager` listener. On network switch (WiFi → mobile data), automatically stops and restarts the VPN tunnel.

**Auto-start on boot:** `BootReceiver` listens for `BOOT_COMPLETED`. If the last state was running, starts the VPN service automatically.

### Notification & Quick Settings

**Foreground Notification** (shown while VPN is active):
- Title: "GostX 运行中"
- Body: current listen address + uptime
- Actions: "停止" button

**Quick Settings Tile** (`GostTileService : TileService`):
- User-addable to the Quick Settings panel
- Tap toggles VPN on/off
- Shows active/inactive state

### State Management

```
GostVpnService
  └── VpnStateRepository (singleton, StateFlow<VpnState>)
        ├── HomeViewModel (observes state → updates UI)
        └── GostTileService (observes state → updates tile icon)
```

`VpnState` = `{ status: Enum(STOPPED/CONNECTING/CONNECTED/ERROR), listenAddr: String, uptime: Duration, error: String? }`

### Permissions & Manifest

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<service android:name=".service.GostVpnService"
    android:permission="android.permission.BIND_VPN_SERVICE"
    android:foregroundServiceType="specialUse">
    <intent-filter>
        <action android:name="android.net.VpnService" />
    </intent-filter>
</service>

<service android:name=".tile.GostTileService"
    android:icon="@drawable/ic_vpn"
    android:label="GostX"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
    <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE" />
    </intent-filter>
</service>

<receiver android:name=".BootReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

### Data Persistence

| Data | Storage |
|------|---------|
| YAML config per profile | `SharedPreferences` (key: `config_profile_<id>`) |
| Profile list & active ID | `SharedPreferences` |
| Last VPN run state (for auto-start) | `SharedPreferences` |

---

## Section 3: macOS Changes

### Directory Rename

```
GostX/GostX/           →  GostX/macos/
GostX/GostX.xcodeproj  →  GostX/macos/GostX.xcodeproj
```

Xcode group references and the root `Makefile` must be updated accordingly.

### SettingsView.swift

- Replace command-line argument editor with YAML editor
- Keep `HighlightedTextEditor`; update highlight rules to YAML keywords (`services:`, `addr:`, `handler:`, `name:`, `chain:`)
- Default value changes from `-L socks5://:1080` to a gost v3 YAML template
- Remove `ArgumentView` — replaced by `YamlConfigView`
- Replace argument description text with a link to gost v3 documentation

### AppDelegate.swift

```swift
// Old
gostRun(args.utf8String, fdPtr)

// New
gostRunYaml(yamlConfig.utf8String, fdPtr)
```

All other logic unchanged: system proxy configuration (SCPreferences), log pipe, menu bar state management.

### Removed Files / Dependencies

| Removed | Reason |
|---------|--------|
| `go/tor.go`, `go/bine/` submodule | Tor support dropped |
| `go/tor-static/` | Tor static libs no longer needed |
| `macos/Arguments.swift` | Replaced by YAML config |
| All `go.mod` Tor dependencies | Tor removed |
| Tor sections in `Makefile` | Build simplified |

---

## Error Handling

| Scenario | Behavior |
|----------|----------|
| Invalid YAML | Android: inline error in ConfigScreen before save. macOS: error shown in menu/log on start attempt |
| gost start failure | VPN service stops cleanly; `VpnState.status = ERROR` with message |
| VPN permission denied (Android) | HomeScreen shows explanation text and re-prompt button |
| Network change | GostVpnService auto-reconnects |
| Port 10808 already in use | `Start()` returns error with descriptive message |

---

## Build System Summary

```
# Root Makefile
android:    cd go && make gostlib.aar
macos:      cd go && make libgost.a && xcodebuild ...

# go/Makefile
libgost.a:      macOS c-archive (arm64 + amd64 universal)
gostlib.aar:    Android .aar via gomobile bind
```

---

## Key Decisions Log

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Design scope | Full all-in-one | Single coherent spec |
| gost version | v3 (go-gost/x) | Active development, YAML config, better API |
| macOS config | YAML editor | Consistent with Android; v3 requirement |
| Android UI framework | Kotlin + Jetpack Compose + Material 3 | Modern, recommended by Google |
| Android navigation | TopAppBar + NavController | 3 screens; bottom nav overkill for <3 destinations |
| VPN mode | Full system proxy (all traffic + DNS) | Most complete functionality |
| tun2socks implementation | xjasonlyu/tun2socks-go | Pure Go; compiles into same .aar |
| Android bridge | gomobile bind → .aar | Idiomatic Go-Android integration |
| Tor support | Removed | Simplifies build; not required |
| Go architecture | Shared gostlib core package | Single source of truth for both platforms |
| iOS extensibility | Supported without gostlib changes | Only needs cmd/ios/ entry point |
