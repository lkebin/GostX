# AGENTS.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test

```bash
# macOS build (Debug)
make macos         # builds Go lib → universal binary → Xcode project

# Android build
make android       # builds gostlib.aar via gomobile → Gradle assembleDebug

# Clean
make clean         # cleans Go + Xcode + Gradle

# Go tests
cd go && go test ./gostlib/...

# Android unit tests
cd android && ./gradlew test

# Android instrumentation tests (requires device/emulator)
cd android && ./gradlew connectedAndroidTest
```

Building the macOS target requires Xcode. Building Android requires `gomobile` and the Android NDK.

## Architecture

**GostX** is a dual-platform (macOS + Android) VPN proxy app built on [gost v3](https://github.com/go-gost/x). The core proxy engine is written in Go and compiled as a C library (macOS) or AAR (Android) that the native UIs call into.

### Go layer (`go/`)

- **`go/cmd/macos/`** — CGo entry point. Exports `gostRunYaml`, `gostStop`, `gostInfo` for the macOS app. `main.go` is thin; real logic lives in `gostlib`.
- **`go/gostlib/`** — Shared Go library used by both platforms:
  - `gostlib.go` — Lifecycle: `Start(yaml)` / `Stop()` / `StartVPNMode(yaml)` / `ValidateConfig()`. `StartVPNMode()` parses the YAML, extracts the `tungo` handler service (TUN-over-gVisor), stores its chain name, then starts the remaining gost services normally.
  - `tun.go` — VPN packet routing via gVisor. `StartVPN(fd, mtu)` takes an Android TUN file descriptor, dups it, wraps it in a gVisor fdbased endpoint, creates a gVisor userspace TCP/IP stack, and routes all sessions through the named gost chain via `gostTransportHandler`. `StopVPN()` tears it down.
  - `vpnlog.go` — Async buffered logging (512-message channel) from gVisor transport goroutines to file. `SetLogFile()` starts a background drain goroutine that batches writes.
  - `logger.go` — Uses `reflect` + `unsafe` to attach a logrus hook to go-gost's internal logger, forwarding gost logs to the same VPN log sink.

### Android (`android/`)

- **MVI architecture** with Jetpack Compose. ViewModels expose `StateFlow`s; screens are composable functions.
- **`GostVpnService`** (`service/GostVpnService.kt`) — Android `VpnService` subclass. Lifecycle: `startVpn()` → validate config → `GostLibBridge.startVPNMode(yaml)` → establish TUN via `Builder.establish()` → `GostLibBridge.startVPN(fd)` → register network callback for reconnect on network change. Internal `GostLibBridge` object calls the Go AAR via reflection.
- **`ConfigRepository`** (`data/ConfigRepository.kt`) — Multi-profile YAML config persistence via `SharedPreferences`. Supports add/rename/delete/set-active profiles. App filter (blacklist/whitelist) for per-app VPN routing.
- **Navigation**: `Screen` sealed class defines routes (Home → ConfigEdit, Logs, Settings → AppFilter). `MainActivity` wires them via NavHost.

### macOS (`macos/`)

- SwiftUI app. Links `libgost.a` (universal: arm64 + amd64) via a bridging header.
- `AppDelegate.swift`, `MacExtrasConfigurator.swift`, `SettingsView.swift`, `Arguments.swift`.

### Key dependencies (Go)

- `github.com/go-gost/x` — gost v3 core: handlers, listeners, dialers, connectors, chains
- `github.com/xjasonlyu/tun2socks/v2` — gVisor-based TUN-to-socks adapter for VPN mode
- `gvisor.dev/gvisor` — Userspace TCP/IP stack powering the VPN TUN path
- `gopkg.in/yaml.v3` — Gost YAML config parsing

### VPN data flow (Android)

```
Apps → TUN fd → gVisor stack (fdbased endpoint) → gostTransportHandler
  → gost chain Router → upstream proxy (SS/HTTP/WSS/etc.) → internet
```

DNS queries to `10.0.0.3:53` (the virtual DNS address) are intercepted by the transport handler and forwarded to the Gost DNS service's loopback address instead of going through the chain.

### Edge cases to be aware of

- The `tungo` handler type is **not** registered in gost's service registry — it's handled entirely inside this codebase. `ValidateConfig()` treats it as an internal type to avoid false "unknown handler" errors.
- `google.golang.org/genproto` is replaced with a local stub (`go/fakepkg/`) because the monorepo zip is too large for restricted network environments.
- Android's `gostlib.aar` is built with `-z max-page-size=16384` for 16KB page alignment (Android 15 requirement).
- VPN lifecycle must call `GostLibBridge.setMemoryLimit(true)` on start (caps heap at 100MB, GOGC=50) and `false` on stop to restore defaults.
- `Stop()` has a 5-second timeout on `serveWg.Wait()` to guarantee it always returns.

### Superpowers

- DO NOT help me submit code in git
