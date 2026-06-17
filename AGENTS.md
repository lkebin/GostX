# AGENTS.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test

```bash
# Android debug build
make android         # builds gostlib.aar via gomobile → Gradle assembleDebug

# Android release build (AAB + APK)
make android-release # builds gostlib.aar → Gradle assembleRelease bundleRelease

# Clean
make clean           # cleans Go + Gradle

# Go tests
cd go && go test ./gostlib/...

# Android unit tests
cd android && ./gradlew test

# Android instrumentation tests (requires device/emulator)
cd android && ./gradlew connectedAndroidTest
```

Building Android requires `gomobile` and the Android NDK.

## Architecture

**GostX** is an Android VPN proxy app built on [gost v3](https://github.com/go-gost/x). The core proxy engine is written in Go and compiled as an AAR (Android Archive) that the native UI calls into.

### Go layer (`go/`)

- **`go/gostlib/`** — Shared Go library:
  - `gostlib.go` — Lifecycle: `Start(yaml)` / `Stop()` / `StartVPNMode(yaml)` / `ValidateConfig()`. `StartVPNMode()` parses the YAML, extracts the `tungo` handler service (TUN-based VPN), stores its chain name, then starts the remaining gost services normally.
  - `tun.go` — VPN packet routing via sing-tun system stack. `StartTun(fd, mtu)` takes an Android TUN file descriptor, dups it, creates a sing-tun Tun device, and starts the system stack. The system stack rewrites IP/TCP headers to redirect traffic to a local listener bound to the TUN address — the OS kernel handles TCP reassembly, no userspace TCP/IP stack needed. `StopTun()` cancels the context and releases the device.
  - `vpnlog.go` — Async buffered logging (512-message channel) from VPN transport goroutines to file. `SetLogFile()` starts a background drain goroutine that batches writes.
  - `logger.go` — Uses `reflect` + `unsafe` to attach a logrus hook to go-gost's internal logger, forwarding gost logs to the same VPN log sink.

### Android (`android/`)

- **MVI architecture** with Jetpack Compose. ViewModels expose `StateFlow`s; screens are composable functions.
- **`GostVpnService`** (`service/GostVpnService.kt`) — Android `VpnService` subclass. Lifecycle: `startVpn()` → validate config → `GostLibBridge.startVPNMode(yaml)` → establish TUN via `Builder.establish()` → `GostLibBridge.startVPN(fd)` → register network callback for reconnect on network change. Internal `GostLibBridge` object calls the Go AAR via reflection.
- **`ConfigRepository`** (`data/ConfigRepository.kt`) — Multi-profile YAML config persistence via `SharedPreferences`. Supports add/rename/delete/set-active profiles. App filter (blacklist/whitelist) for per-app VPN routing.
- **Navigation**: `Screen` sealed class defines routes (Home → ConfigEdit, Logs, Settings → AppFilter). `MainActivity` wires them via NavHost.

### Key dependencies (Go)

- `github.com/go-gost/x` — gost v3 core: handlers, listeners, dialers, connectors, chains
- `github.com/sagernet/sing-tun` — TUN device + system stack (OS kernel TCP, IP header rewriting)
- `github.com/sagernet/sing` — Buffer pool, metadata types, network interfaces used by sing-tun
- `gopkg.in/yaml.v3` — Gost YAML config parsing

### VPN data flow (Android)

```
Apps → TUN fd → sing-tun system stack (IP header rewrite)
  → OS kernel TCP/IP → singTunHandler (NewConnectionEx / NewPacketConnectionEx)
  → gost chain Router → upstream proxy (SS/HTTP/WSS/etc.) → internet
```

The system stack avoids a userspace TCP/IP implementation: it rewrites destination IP/port in raw packets to redirect them to a local TCP listener bound to the TUN interface address, letting the OS kernel handle TCP reassembly and connection state.

DNS queries to `10.0.0.3:53` (the virtual DNS address) are intercepted by the transport handler and forwarded to the Gost DNS service's loopback address instead of going through the chain.

### Edge cases to be aware of

- The `tungo` handler type is **not** registered in gost's service registry — it's handled entirely inside this codebase. `ValidateConfig()` treats it as an internal type to avoid false "unknown handler" errors.
- `google.golang.org/genproto` is replaced with a local stub (`go/fakepkg/`) because the monorepo zip is too large for restricted network environments.
- Android's `gostlib.aar` is built with `-z max-page-size=16384` for 16KB page alignment (Android 15 requirement).
- VPN lifecycle must call `GostLibBridge.setMemoryLimit(true)` on start (caps heap at 100MB, GOGC=50) and `false` on stop to restore defaults.
- `Stop()` has a 5-second timeout on `serveWg.Wait()` to guarantee it always returns.

### Superpowers

- DO NOT help me submit code in git

## Multilingual (i18n)

- Default strings in `android/app/src/main/res/values/strings.xml` must be **English** — concise, matching the existing style (e.g. "Running", "No logs", "Log level")
- Chinese translations live in `android/app/src/main/res/values-zh/strings.xml`
- **Every key must exist in both files** — the two files must have identical `name="..."` sets
- When adding a new string resource, add it to both files at the same time
