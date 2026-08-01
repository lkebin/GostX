# Sync macOS logging with libgost/Android timezone update

- **Date:** 2026-08-01
- **Status:** Approved (design)
- **Scope:** macOS VPN-mode logging only
- **Related commit:** `d6dc885` "fix: unify Go logging to slog and fix Android timezone"

## 1. Background

The libgost + Android logging was reworked in commit `d6dc885`:

- Go logging was unified onto `log/slog` (internal to libgost — no change to the
  Go↔app bridge API used by consumers).
- A new exported function `SetTimezone(name string, offsetSeconds int)` was added
  so Go timestamps the platform's local time instead of UTC. **Android calls it**
  (`GostVpnService.kt:194` → `LibgostBridge.setTimezone(TimeZone.getDefault())`)
  before configuring log level/file, passing the IANA zone ID and current UTC offset.

### Why Android needed it

Android does not set `$TZ` for app processes and has no `/etc/localtime`, so Go's
`time.Local` falls back to UTC. The fix pushes the platform timezone into Go.

### macOS situation

macOS normally provides a correct `time.Local` to processes, so gost timestamps are
usually already correct. The NetworkExtension sandbox and cross-platform parity are
the reasons to add the call on macOS as well. `SetTimezone` is idempotent (early
returns when the zone+offset key is unchanged), so repeated tunnel starts are safe.

## 2. Current macOS bridge state

- **VPN mode** (`macos/GostXTunnel/PacketTunnelProvider.swift`,
  `setWorkDirAndLog()`) configures Go logging:
  `LibgostSetLogMaxSize` → `LibgostSetLogFile` → `LibgostSetLogLevel`.
  It does **not** call `LibgostSetTimezone`.
- **Proxy mode** (`macos/GostX/AppDelegate.swift`, `startProxyMode()`) calls
  `LibgostStartGost` directly and configures **no** Go logging at all. Per the
  user's decision, proxy mode is explicitly **out of scope** for this change.
- The in-app log viewer (`LogViewModel` / `LogContentView`) reads
  `<App Group container>/gost.log`, which is the same file the extension writes to.

## 3. Goal

Mirror the Android update on the macOS side: push the platform timezone into Go in
the VPN-mode setup, so gost log timestamps in `gost.log` match the system clock and
are consistent with the Android path.

## 4. Approach (Option A — minimal, approved)

Add a single `LibgostSetTimezone` call inside `setWorkDirAndLog()` in
`PacketTunnelProvider.swift`, placed before the existing logging calls to mirror
Android's ordering (timezone first, then level/file).

### Exact change

`macos/GostXTunnel/PacketTunnelProvider.swift`, in `setWorkDirAndLog()`:

```swift
let level = AppGroupConfig.loggingEnabled ? AppGroupConfig.logLevel : "off"
let logFile = containerURL.appendingPathComponent("gost.log").path

// Push the platform timezone so Go timestamps match the system clock.
// (Android does the same; harmless but safe inside the NE sandbox.)
let tz = TimeZone.current
LibgostSetTimezone(tz.identifier, Int(tz.secondsFromGMT()))

LibgostSetLogMaxSize(2 * 1024 * 1024)
LibgostSetLogFile(logFile, nil)
LibgostSetLogLevel(level)
```

### Bindings

- `TimeZone.current.identifier` → IANA zone name (e.g. `"Asia/Shanghai"`), passed as `name`.
- `TimeZone.current.secondsFromGMT()` → current UTC offset in seconds (DST-aware,
  equivalent to Android's `getOffset(now) / 1000`), passed as `offsetSeconds`.
- libgost exports `SetTimezone(name string, offsetSeconds int)`. gomobile generates
  `LibgostSetTimezone(_ name: String, _ offsetSeconds: Int)` in the xcframework.

## 5. Build note

`LibgostSetTimezone` only exists in the xcframework after libgost is rebuilt. The
normal `make macos` flow already rebuilds it first
(`build-libgost-xcframework` → copies into `macos/Frameworks/Libgost.xcframework`),
so no extra step is required beyond a normal build. A plain `xcodebuild` against a
stale framework would fail to link the new symbol.

## 6. Out of scope

- Proxy-mode Go logging setup in `AppDelegate.startProxyMode()` is deliberately
  unchanged (user decision: "暂时不需要支持 proxy mode").
- No App Group schema change, no i18n strings, no change to the log viewer UI.

## 7. Testing / verification

1. `make macos` builds successfully (confirms the new `LibgostSetTimezone` symbol
   links against the freshly built xcframework).
2. Run the app in VPN mode, start the tunnel, open the log viewer.
3. Confirm gost log lines in `gost.log` carry timestamps in the system local time
   (matching the `[APP]` lines written by `AppLogger`, which already use local time).
4. Restart the tunnel once; confirm no errors and timestamps remain consistent
   (idempotent `SetTimezone`).
