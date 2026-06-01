# Log Refactor Design

**Date:** 2026-05-31  
**Branch:** multi-config  
**Status:** Approved

## Goals

1. **Enhanced start/stop logging** — Timestamps on every log line + milestone logs at key blocking steps so timing is immediately visible.
2. **Go direct log writing** — Android passes log file path to Go; Go owns a drain goroutine that writes VPN traffic logs directly to file. Removes the Kotlin `vpnLogJob` polling loop.
3. **LogViewModel follow/scroll separation** — `isFollowing` becomes a pure auto-scroll flag; polling always reads new lines regardless of follow state.
4. **Auto-pause follow on scroll-up** — `LogScreen` automatically sets `isFollowing = false` when the user scrolls away from the bottom.

---

## Architecture

### Log Write Pipeline (after)

```
VPN traffic events  →  logVPN()  →  vpnLogCh  ──┐
gost internal logs  →  logVPN()  ───────────────┤
                                    Go drain     │  O_APPEND
                                    goroutine ───┴──→ gostx.log ←── LogRepository.append()
                                                                      (Kotlin service events)
```

Both sides open the file with `O_APPEND`. On Linux, each `write()` syscall with `O_APPEND` is atomic for writes ≤ PIPE_BUF (4 KB); all log lines are well below this limit, so no file-level locking is needed.

### Log Read Pipeline (unchanged)

```
gostx.log  →  LogViewModel.pollJob (1 s)  →  _lines StateFlow  →  LazyColumn
                                                    ↑
                                       isFollowing = auto-scroll only
```

---

## Component Changes

### 1. `go/gostlib/vpnlog.go`

**Add `SetLogFile(path string) error`**

- Opens the file with `O_CREATE | O_WRONLY | O_APPEND | 0644`.
- Starts `drainLogToFile` goroutine exactly once (guarded by `sync.Once`).
- Returns an error if the file cannot be opened (gomobile maps this to `throws Exception`).

**Add `drainLogToFile(f *os.File)` goroutine**

Runs for the lifetime of the process. Batches writes efficiently:

```go
func drainLogToFile(f *os.File) {
    for {
        msg := <-vpnLogCh          // block until next message
        var b strings.Builder
        b.WriteString(msg); b.WriteByte('\n')
        for {                      // drain remaining without blocking
            select {
            case msg = <-vpnLogCh:
                b.WriteString(msg); b.WriteByte('\n')
            default:
                f.WriteString(b.String())
                goto next
            }
        }
    next:
    }
}
```

**`resetVPNStats()` — kept as-is**  
Still drains `vpnLogCh` on session start as a safety net for stale messages. With the drain goroutine running concurrently, the channel will usually be empty already; the drain loop exits immediately in that case.

**`logVPN()` — unchanged**  
Still non-blocking channel send; drain goroutine is the sole consumer.

---

### 2. `android/app/src/main/kotlin/.../data/LogRepository.kt`

**`append(line: String)` — auto-timestamp**

Prepends `HH:mm:ss.SSS` to every line:

```kotlin
fun append(line: String) {
    val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    // write "$ts $line\n" ...
}
```

All Kotlin service events and (via drain goroutine) all Go events get uniform timestamps. No manual timing needed at call sites.

**`deleteLog()` — truncate instead of delete**

```kotlin
fun deleteLog() {
    synchronized(this) { logFile?.writeText("") }
}
```

Truncating rather than deleting keeps Go's file descriptor valid. After truncation:
- Go's `O_APPEND` writes resume from offset 0 (kernel atomically seeks to new EOF = 0).
- `LogViewModel`'s existing `fileLen < offset` check detects the truncation and resets `fileOffset` to 0.

---

### 3. `android/app/src/main/kotlin/.../service/GostVpnService.kt`

**`onCreate()` — initialise repo and tell Go the log path**

```kotlin
override fun onCreate() {
    super.onCreate()
    LogRepository.init(this)
    GostLibBridge.setLogFile(LogRepository.getLogFile().absolutePath)
        .onFailure { Log.e("GostX", "setLogFile failed: ${it.message}") }
}
```

**Remove `vpnLogJob` entirely**  
Fields, `startVpnLogPolling()`, and all `vpnLogJob?.cancel()` / `vpnLogJob = null` call sites are deleted.

**Simplify `registerServiceReceiver()`**  
`ACTION_SCREEN_OFF` and `ACTION_SCREEN_ON` branches are removed — they only existed to start/stop `vpnLogJob`. The BroadcastReceiver now only handles `DEVICE_IDLE_MODE_CHANGED`.

**Simplify `handleIdleModeChanged()`**  
Doze enter: only `unregisterNetworkCallback()`.  
Doze exit: only `registerNetworkCallback()`.  
No more vpnLogJob management.

**Milestone logs in `startVpn()`**

```
"[start] loading config and starting gost..."
"[start] gost ready"
"[start] establishing VPN interface..."
"[start] VPN interface ready"
"[start] starting tun2socks..."
"[start] VPN connected, addr: <addr>"
```

**Milestone logs in `stopVpn()`**

```
"[stop] stopping tun2socks..."
"[stop] stopping gost..."
"[stop] VPN stopped"
```

With automatic timestamps these produce output like:
```
23:10:01.042 [start] loading config and starting gost...
23:10:01.891 [start] gost ready
23:10:01.895 [start] establishing VPN interface...
23:10:01.902 [start] VPN interface ready
...
```

**`GostLibBridge` — add `setLogFile`**

```kotlin
fun setLogFile(path: String): Result<Unit> =
    runCatching { invoke("setLogFile", path); Unit }
```

---

### 4. `android/app/src/main/kotlin/.../ui/log/LogViewModel.kt`

**`startPolling()` — always read, regardless of `isFollowing`**

```kotlin
fun startPolling() {
    pollJob?.cancel()
    pollJob = viewModelScope.launch(Dispatchers.IO) {
        while (isActive) {
            delay(1000)
            appendNewLines()   // always — isFollowing no longer gates reads
        }
    }
}
```

**Add `setFollowing(value: Boolean)`**

```kotlin
fun setFollowing(value: Boolean) {
    _isFollowing.value = value
    if (value) viewModelScope.launch(Dispatchers.IO) { appendNewLines() }
}
```

Calling `setFollowing(true)` immediately reads missed lines so the view is current before the next 1-second tick.

**`toggleFollow()` — delegates to `setFollowing`**

```kotlin
fun toggleFollow() = setFollowing(!_isFollowing.value)
```

---

### 5. `android/app/src/main/kotlin/.../ui/log/LogScreen.kt`

**Auto-pause follow when scrolling away from bottom**

```kotlin
LaunchedEffect(listState) {
    snapshotFlow { listState.isScrollInProgress }
        .collect { scrolling ->
            if (!scrolling && viewModel.isFollowing.value) {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@collect
                if (last < info.totalItemsCount - 1) {
                    viewModel.setFollowing(false)
                }
            }
        }
}
```

Fires when scroll settles. Only disables following when the user has scrolled up (not at the last item). Auto-scroll uses synchronous `scrollToItem` (instant, no animation) to jump to the last item, so `last < totalItemsCount - 1` is false and following is not disabled.

Re-enabling follow requires the user to tap the play button in the top bar (existing behaviour).

---

## AAR Rebuild

`SetLogFile` is a new exported Go function. The AAR must be rebuilt with `cd go && make gostlib.aar` and force-added to git.

---

## Edge Cases

**Stale messages on rapid Stop → Start**  
If the user taps Start immediately after Stop, the drain goroutine may still be writing leftover messages from the previous session when Kotlin truncates the file. These messages appear at the top of the new session's log. This window is tiny (milliseconds) and the impact is cosmetic. No action needed.

**`SimpleDateFormat` thread safety**  
`SimpleDateFormat` is not thread-safe. `LogRepository.append()` is already `synchronized(this)`, so a single instance can be created once and reused safely inside the synchronized block.

**Go file descriptor after truncation**  
Verified: on Linux, truncating a file while another process holds an `O_APPEND` fd causes the next write from that fd to go to offset 0. This is the desired behaviour.
