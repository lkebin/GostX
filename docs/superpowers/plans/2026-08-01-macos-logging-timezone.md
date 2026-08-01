# macOS Logging Timezone Sync — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Inject the platform timezone into Go from the macOS VPN-mode tunnel provider so gost log timestamps in `gost.log` match the system clock, mirroring the Android update (commit `d6dc885`).

**Architecture:** A single call to the already-exported `LibgostSetTimezone(name, offsetSeconds)` is added inside `PacketTunnelProvider.setWorkDirAndLog()`, placed before the existing `LibgostSetLogLevel` call — the exact analog of Android's `setTimezone(TimeZone.getDefault())`. gomobile exposes `SetTimezone` as `LibgostSetTimezone(_:_:)` in the xcframework; `make macos` rebuilds that framework first, so the new symbol links without extra steps.

**Tech Stack:** Swift, NetworkExtension (`NEPacketTunnelProvider`), Libgost xcframework (Go + gomobile), gost `slog` logging.

## Global Constraints

- Scope is **VPN mode only**; proxy mode (`AppDelegate.startProxyMode()`) is intentionally left unchanged (user decision: "暂时不需要支持 proxy mode").
- Mirror Android: pass IANA zone `TimeZone.current.identifier` and current UTC offset `TimeZone.current.secondsFromGMT()` as `name` / `offsetSeconds`.
- `LibgostSetTimezone` is idempotent (early-returns on unchanged zone+offset), so repeated tunnel starts are safe.
- No App Group schema change, no i18n string changes, no change to the log viewer UI.
- Build via `make macos` (which rebuilds the xcframework from `libgost/` before compiling Swift), so the new symbol is present — a bare `xcodebuild` against a stale framework would fail to link.

---

## File Structure

- **Modify:** `macos/GostXTunnel/PacketTunnelProvider.swift`
  - Method `setWorkDirAndLog()` — add the `LibgostSetTimezone` call between computing `level`/`logFile` and the existing `LibgostSetLogMaxSize`/`SetLogFile`/`SetLogLevel` calls. One responsibility: configure the Go side of logging (work dir, file, level, timezone) for the tunnel provider.

No new files. No test files (see Note in Task 1 about why automated unit testing is not applicable here).

---

### Task 1: Inject platform timezone into Go logging

**Files:**
- Modify: `macos/GostXTunnel/PacketTunnelProvider.swift` (method `setWorkDirAndLog()`)

**Interfaces:**
- Consumes: `AppGroupConfig.containerURL`, `AppGroupConfig.loggingEnabled`, `AppGroupConfig.logLevel` (already used in this method); `TimeZone.current` (Foundation); `LibgostSetTimezone(_ name: String, _ offsetSeconds: Int)` (gomobile-generated binding from `libgost.SetTimezone`).
- Produces: same observable behavior as before, plus gost now timestamps in the system local zone. No new API surface.

- [ ] **Step 1: Add the timezone call**

In `macos/GostXTunnel/PacketTunnelProvider.swift`, inside `private func setWorkDirAndLog()`, replace:

```swift
        let level = AppGroupConfig.loggingEnabled ? AppGroupConfig.logLevel : "off"
        let logFile = containerURL.appendingPathComponent("gost.log").path
        LibgostSetLogMaxSize(2 * 1024 * 1024)
        LibgostSetLogFile(logFile, nil)
        LibgostSetLogLevel(level)
```

with:

```swift
        let level = AppGroupConfig.loggingEnabled ? AppGroupConfig.logLevel : "off"
        let logFile = containerURL.appendingPathComponent("gost.log").path

        // Push the platform timezone so Go timestamps match the system clock.
        // Android does the same (setTimezone(TimeZone.getDefault())); harmless
        // but safe inside the NetworkExtension sandbox.
        let tz = TimeZone.current
        LibgostSetTimezone(tz.identifier, tz.secondsFromGMT())

        LibgostSetLogMaxSize(2 * 1024 * 1024)
        LibgostSetLogFile(logFile, nil)
        LibgostSetLogLevel(level)
```

Note: `TimeZone.current.identifier` is the IANA zone (e.g. `"Asia/Shanghai"`); `TimeZone.current.secondsFromGMT()` is the current UTC offset in seconds (DST-aware), matching Android's `getOffset(now) / 1000`. Go `SetTimezone(name string, offsetSeconds int)` maps to Swift `LibgostSetTimezone(_:_:)` with both params as `Int`, so no conversion needed.

- [ ] **Step 2: Build to confirm the new symbol links**

Run from repo root:

```bash
make macos
```

Expected: the `build-libgost-xcframework` step rebuilds `libgost/Libgost.xcframework` (now containing `SetTimezone`), then `xcodebuild` compiles `GostX.xcodeproj` and **succeeds** — proving `LibgostSetTimezone` resolves and links. (If you instead run a bare `xcodebuild` against a pre-existing `macos/Frameworks/Libgost.xcframework` that predates this libgost change, the link will fail with `undefined symbol`; that is the signal the xcframework must be rebuilt via `make macos`.)

- [ ] **Step 3: Commit**

```bash
git add macos/GostXTunnel/PacketTunnelProvider.swift
git commit -m "fix: inject platform timezone into Go logging on macOS tunnel provider"
```

> **Note on testing:** `setWorkDirAndLog()` is a `private` method on `NEPacketTunnelProvider` that depends on the App Group container and the Libgost framework, so it has no practical standalone unit test in this repo. The compile-time gate (Step 2) proves the binding exists and links; runtime confirmation is Task 2. No contrived test is added (YAGNI).

---

### Task 2: Verify timestamps at runtime

**Files:**
- None (manual verification against a running tunnel)

**Interfaces:**
- Consumes: the built `GostX.app` + `GostXTunnel.appex` from Task 1; the in-app log viewer (`LogViewModel` / `LogContentView`) reading `<App Group container>/gost.log`.

- [ ] **Step 1: Run the app in VPN mode and start the tunnel**

Launch the built app, ensure the active config contains `type: tungo` (VPN mode), enable logging (Settings → Enable Logging, level e.g. `info`), and start the tunnel.

- [ ] **Step 2: Open the log viewer and inspect timestamps**

Open the log view. Confirm gost log lines in `gost.log` carry timestamps in the **system local time**, matching the `[APP]` lines written by `AppLogger` (which already uses local time via `DateFormatter`). Previously (before this change) gost lines could differ if the NE sandbox lacked tzdata; now they agree.

- [ ] **Step 3: Restart the tunnel once**

Stop and restart the tunnel. Confirm no errors and that timestamps remain consistent — exercising `SetTimezone`'s idempotent early-return path.

- [ ] **Step 4: Record result**

If timestamps match local time and survive a restart, the task is done. (If the build in Task 1 failed to link `LibgostSetTimezone`, return to Task 1 Step 2 and ensure `make macos` rebuilt the xcframework.)

---

## Self-Review Notes

- **Spec coverage:** timezone call (Task 1 Step 1) ✓; placed before log-level setup to mirror Android ✓; VPN-mode only, proxy mode untouched ✓; no i18n/App Group/schema changes ✓; build note (xcframework rebuild) ✓; verification (Task 2) ✓.
- **Placeholders:** none — exact Swift code and exact `make macos` command supplied.
- **Type consistency:** `LibgostSetTimezone(_ name: String, _ offsetSeconds: Int)` matches the Go `SetTimezone(name string, offsetSeconds int)` export; `TimeZone.current.identifier` (String) and `TimeZone.current.secondsFromGMT()` (Int) align with the parameters.
