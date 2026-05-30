# Log File Storage & Tail-F UI Design

**Date:** 2026-05-30  
**Status:** Approved  

## Problem

Current log system stores all log lines in a `MutableStateFlow<List<String>>` in memory (capped at 1000 lines). This increases memory pressure and prevents the app from persisting logs across UI navigation. The log screen also always auto-scrolls to the bottom, making it impossible to browse historical entries while the service is running.

## Goals

1. Write logs to a file (`filesDir/gostx.log`) instead of holding them in memory.
2. Support `tail -f`-style live tailing in the log screen, with a pause/resume toggle.
3. Add a toggle button (top-right of the log screen) to switch between following and browsing modes.
4. Delete the log file each time the VPN service starts or stops, so each session begins fresh.

## Non-Goals

- Log file size limits or rotation (delete-on-start/stop is sufficient).
- Changes to the Go library (`vpnlog.go`, `logger.go`).
- Persisting logs across app processes or across sessions.

## Architecture

### Data Flow

```
GostVpnService (every 1s)
  └── GostLibBridge.getVPNLog()    ← Go side unchanged
  └── internal log() calls
        ↓
     LogRepository.append(line)
        ↓ append-write
     filesDir/gostx.log
        ↑ read new bytes every 1s (when following)
     LogViewModel
        ↓ StateFlow<List<String>>
     LogScreen (Compose UI)
```

## Component Design

### `data/LogRepository.kt` (rewrite)

Replaces the in-memory `StateFlow<List<String>>` with a file-backed writer.

**API:**
- `fun init(context: Context)` — stores `filesDir` reference; called from `GostVpnService.onCreate()` and available to `LogViewModel` via `Application` context.
- `fun append(line: String)` — appends `"$line\n"` to `gostx.log`; thread-safe via `synchronized`; silently ignores I/O errors (logging must not affect VPN operation).
- `fun deleteLog()` — deletes the log file; called at VPN start and stop.
- `val logFile: File` — the `File` reference, exposed for `LogViewModel` to read.

No log content is held in memory inside `LogRepository`.

### `service/GostVpnService.kt` (minor change)

- `startVpn()`: call `LogRepository.deleteLog()` as the **first** statement, before any `log()` call.
- `stopVpn()`: call `LogRepository.deleteLog()` as the **first** statement, before any `log()` call.

Both invocations open a clean slate; each session's log file contains only events from that start or stop cycle.

- `private fun log(msg: String)` — unchanged; still calls `LogRepository.append(msg)`.

### `ui/log/LogViewModel.kt` (new file)

Manages all log screen state. Instantiated as a standard `ViewModel` via `viewModel()`.

**State:**
- `val lines: StateFlow<List<String>>` — lines currently displayed (last 2000, to prevent UI jank on long sessions).
- `val isFollowing: StateFlow<Boolean>` — live-tail mode; defaults to `true`.
- `private var fileOffset: Long` — byte offset of last-read position in `gostx.log`.

**Lifecycle:**
- `fun loadInitial()` — called when the screen enters composition; reads the entire file, populates `lines`, sets `fileOffset` to file length.
- Polling coroutine starts in `init {}`: while `isFollowing`, every 1 second read bytes from `fileOffset` to EOF, split on `\n`, append non-empty lines to `lines` (capped at 2000), advance `fileOffset`.

**Actions:**
- `fun toggleFollow()` — flips `isFollowing`.
  - `true → false`: polling coroutine pauses; `fileOffset` is not advanced. UI is free to scroll.
  - `false → true`: immediately reads all bytes from `fileOffset` to EOF, appends them to `lines`, advances `fileOffset`, then resumes the 1-second polling. `LogScreen` scrolls to bottom via `LaunchedEffect` on the `isFollowing` transition.
- `fun copyAll(): String` — reads the full file from byte 0 (not from `lines`), returns complete content as a string.
- `fun clearLog()` — calls `LogRepository.deleteLog()`, resets `lines` to empty, resets `fileOffset` to 0.

**Edge cases:**
- File does not exist: `loadInitial()` sets `lines = emptyList()`, `fileOffset = 0`.
- File deleted while screen is open (service start/stop): next read attempt gets `FileNotFoundException`; caught silently, `lines` cleared, `fileOffset` reset to 0.

### `ui/log/LogScreen.kt` (update)

- Remove direct `LogRepository.logs` dependency; use `LogViewModel`.
- `val lines by viewModel.lines.collectAsState()`
- `val isFollowing by viewModel.isFollowing.collectAsState()`
- Auto-scroll `LaunchedEffect`: only fires when `isFollowing == true` and `lines.size` changes.
- **New top-bar action** (rightmost, before Copy): `IconButton` with:
  - Icon: `Icons.Filled.PlayArrow` when `isFollowing == false` (tap to resume)
  - Icon: `Icons.Filled.Pause` when `isFollowing == true` (tap to pause)
  - `contentDescription`: `stringResource(R.string.log_follow_on/off)`
- Copy button calls `viewModel.copyAll()` (reads file, not in-memory list).
- Clear button calls `viewModel.clearLog()`.

### `res/values/strings.xml` + `res/values-zh/strings.xml`

Add:
```xml
<string name="log_follow_on">Resume live tail</string>
<string name="log_follow_off">Pause live tail</string>
```

Chinese:
```xml
<string name="log_follow_on">开启动态载入</string>
<string name="log_follow_off">暂停动态载入</string>
```

## Files Changed

| File | Change |
|---|---|
| `data/LogRepository.kt` | Rewrite: in-memory Flow → file writer |
| `service/GostVpnService.kt` | Add `deleteLog()` at start/stop of `startVpn()`/`stopVpn()` |
| `ui/log/LogViewModel.kt` | New: file reading, polling, follow state |
| `ui/log/LogScreen.kt` | Use `LogViewModel`; add follow toggle button |
| `res/values/strings.xml` | Add `log_follow_on`, `log_follow_off` |
| `res/values-zh/strings.xml` | Add Chinese strings |

## Go Side

No changes required. `vpnlog.go` and `logger.go` are unchanged.
