# Log Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Kotlin-side VPN log polling loop with a Go-owned drain goroutine writing directly to the log file; add automatic timestamps to all logs; add milestone logs for start/stop timing; and make the log screen auto-pause follow when the user scrolls up.

**Architecture:** Android passes the log file path to Go via `SetLogFile(path)`. A long-lived Go drain goroutine batches messages from `vpnLogCh` directly to the file. Kotlin service events still write to the same file via `LogRepository.append()` using `O_APPEND` (safe on Linux for small writes). `isFollowing` in `LogViewModel` becomes a pure auto-scroll flag — polling always reads new lines regardless.

**Tech Stack:** Go (gomobile/gostlib), Kotlin/Coroutines, Jetpack Compose

---

## Files Changed

| File | Change |
|---|---|
| `go/gostlib/vpnlog.go` | Add `SetLogFile`, `drainLogToFile`; keep `GetVPNLog` but it becomes unused |
| `go/gostlib/gostlib_test.go` | Add `TestSetLogFile` |
| `android/app/libs/gostlib.aar` | Rebuild (force-add, gitignored) |
| `android/app/libs/gostlib-sources.jar` | Rebuild (force-add, gitignored) |
| `android/app/src/main/kotlin/.../data/LogRepository.kt` | Add timestamp in `append()`; truncate in `deleteLog()` |
| `android/app/src/main/kotlin/.../service/GostVpnService.kt` | Add `setLogFile` call; remove `vpnLogJob`; simplify receiver; add milestone logs |
| `android/app/src/main/kotlin/.../ui/log/LogViewModel.kt` | Always poll; add `setFollowing()` |
| `android/app/src/main/kotlin/.../ui/log/LogScreen.kt` | Auto-pause follow on scroll |
| `android/app/src/test/kotlin/.../LogViewModelLogicTest.kt` | Tests for truncation and `setFollowing` API |

---

### Task 1: Go — Add `SetLogFile` and drain goroutine

**Files:**
- Modify: `go/gostlib/vpnlog.go`
- Modify: `go/gostlib/gostlib_test.go`

- [ ] **Step 1: Write the failing Go test**

Add to `go/gostlib/gostlib_test.go` (inside the `package gostlib` block, after existing tests). The file already imports `os`, `strings`, `testing`, `time` — no new imports needed:

```go
func TestSetLogFile(t *testing.T) {
	f, err := os.CreateTemp("", "vpnlog_test_*")
	if err != nil {
		t.Fatal(err)
	}
	path := f.Name()
	f.Close()
	defer os.Remove(path)

	if err := SetLogFile(path); err != nil {
		t.Fatalf("SetLogFile: %v", err)
	}

	logVPN("hello %s", "world")
	logVPN("second line")

	// Give drain goroutine time to write
	time.Sleep(100 * time.Millisecond)

	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimRight(string(got), "\n"), "\n")
	if len(lines) != 2 {
		t.Fatalf("expected 2 lines, got %d: %q", len(lines), string(got))
	}
	if lines[0] != "hello world" || lines[1] != "second line" {
		t.Errorf("unexpected lines: %v", lines)
	}
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
cd /Users/kbliu/Workspace/project/GostX/.worktrees/multi-config/go/gostlib
go test -run TestSetLogFile -v 2>&1 | head -20
```

Expected: `undefined: SetLogFile`

- [ ] **Step 3: Implement `SetLogFile` and `drainLogToFile` in `vpnlog.go`**

Replace the entire `go/gostlib/vpnlog.go` with:

```go
package gostlib

import (
	"fmt"
	"os"
	"strings"
	"sync"
	"sync/atomic"
)

// vpnLogCh buffers log messages from the gVisor transport handler.
// Capacity 512 means we can hold ~512 messages before dropping.
var vpnLogCh = make(chan string, 512)

// VPN connection counters; reset by resetVPNStats.
var (
	vpnTCPConns    int64 // total TCP sessions dispatched
	vpnUDPConns    int64 // total UDP sessions dispatched
	vpnFailedConns int64 // sessions where router.Dial failed
)

var logDrainOnce sync.Once

func logVPN(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	select {
	case vpnLogCh <- msg:
	default:
		// buffer full – drop to avoid blocking the packet-dispatch goroutine
	}
}

// SetLogFile tells gostlib to write VPN log messages directly to the given
// file path. The file is opened with O_APPEND so it can be safely shared with
// the Kotlin logger. Call once on app startup; subsequent calls are no-ops.
func SetLogFile(path string) error {
	var openErr error
	logDrainOnce.Do(func() {
		f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
		if err != nil {
			openErr = err
			return
		}
		go drainLogToFile(f)
	})
	return openErr
}

// drainLogToFile runs for the lifetime of the process. It blocks on vpnLogCh
// then batches any additional messages that arrived concurrently before writing,
// minimising the number of write syscalls.
func drainLogToFile(f *os.File) {
	for {
		msg := <-vpnLogCh // block until next message
		var b strings.Builder
		b.WriteString(msg)
		b.WriteByte('\n')
	drain:
		for {
			select {
			case msg = <-vpnLogCh:
				b.WriteString(msg)
				b.WriteByte('\n')
			default:
				break drain
			}
		}
		f.WriteString(b.String()) //nolint:errcheck
	}
}

// GetVPNLog drains all pending VPN log messages and returns them
// newline-separated. Retained for external tooling; not called by the app
// when SetLogFile has been configured.
func GetVPNLog() string {
	var sb strings.Builder
	for {
		select {
		case msg := <-vpnLogCh:
			sb.WriteString(msg)
			sb.WriteByte('\n')
		default:
			return sb.String()
		}
	}
}

func resetVPNStats() {
	atomic.StoreInt64(&vpnTCPConns, 0)
	atomic.StoreInt64(&vpnUDPConns, 0)
	atomic.StoreInt64(&vpnFailedConns, 0)
	// drain any stale log messages from a previous session
	for {
		select {
		case <-vpnLogCh:
		default:
			return
		}
	}
}
```

- [ ] **Step 4: Run test to confirm it passes**

```bash
cd /Users/kbliu/Workspace/project/GostX/.worktrees/multi-config/go/gostlib
go test -run TestSetLogFile -v
```

Expected: `PASS`

- [ ] **Step 5: Run full Go test suite**

```bash
cd /Users/kbliu/Workspace/project/GostX/.worktrees/multi-config/go/gostlib
go test -v -timeout 60s 2>&1 | tail -20
```

Expected: all tests pass (some may be skipped due to Linux-only gVisor constraint)

- [ ] **Step 6: Build AAR**

```bash
cd /Users/kbliu/Workspace/project/GostX/.worktrees/multi-config/go
make gostlib.aar 2>&1 | tail -5
```

Expected: `gostlib.aar` and `gostlib-sources.jar` updated in `android/app/libs/`.

- [ ] **Step 7: Verify `setLogFile` method exists in AAR**

```bash
cd /tmp && unzip -o /Users/kbliu/Workspace/project/GostX/.worktrees/multi-config/android/app/libs/gostlib.aar classes.jar -d aar_check 2>/dev/null \
  && javap -classpath aar_check/classes.jar gostlib.Gostlib 2>&1 | grep -E 'setLogFile|setWorkDir'
```

Expected output contains: `public static native void setLogFile(java.lang.String) throws java.lang.Exception;`

- [ ] **Step 8: Commit**

```bash
cd /Users/kbliu/Workspace/project/GostX/.worktrees/multi-config
git add go/gostlib/vpnlog.go go/gostlib/gostlib_test.go
git add -f android/app/libs/gostlib.aar android/app/libs/gostlib-sources.jar
git commit -m "feat(go): add SetLogFile with drain goroutine for direct log file writing

- SetLogFile opens the log file with O_APPEND and starts drainLogToFile goroutine
- drainLogToFile batches vpnLogCh messages and writes directly to file
- Eliminates need for Kotlin-side vpnLogJob polling loop
- sync.Once ensures the goroutine is started exactly once per process

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: LogRepository — Timestamps and truncate

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/data/LogRepository.kt`
- Modify: `android/app/src/test/kotlin/cn/liukebin/GostX/LogViewModelLogicTest.kt`

- [ ] **Step 1: Write failing tests for timestamp format and truncate behaviour**

Add to `android/app/src/test/kotlin/cn/liukebin/GostX/LogViewModelLogicTest.kt`, after the existing imports and before the `LogViewModelLogicTest` class:

```kotlin
import cn.liukebin.GostX.data.LogRepository
```

Add these tests inside `LogViewModelLogicTest` (after the existing `@After cleanup()`):

```kotlin
@Test fun `LogRepository append prepends HH-mm-ss-SSS timestamp`() {
    LogRepository.initForTest(logFile)
    LogRepository.append("test message")
    val text = logFile.readText()
    // e.g. "23:10:01.042 test message\n"
    assertTrue(
        "Expected timestamp prefix, got: $text",
        text.matches(Regex("\\d{2}:\\d{2}:\\d{2}\\.\\d{3} test message\n"))
    )
}

@Test fun `LogRepository deleteLog truncates file not deletes it`() {
    LogRepository.initForTest(logFile)
    LogRepository.append("some content")
    assertTrue(logFile.exists())
    assertTrue(logFile.length() > 0)

    LogRepository.deleteLog()

    assertTrue("file should still exist after deleteLog", logFile.exists())
    assertEquals("file should be empty after deleteLog", 0L, logFile.length())
}

@Test fun `readFileFrom treats empty truncated file as reset`() {
    logFile.writeText("line1\nline2\n")
    val mid = logFile.length()
    logFile.writeText("") // simulate LogRepository.deleteLog truncation
    val (lines, offset) = readFileFrom(logFile, mid)
    assertEquals(emptyList<String>(), lines)
    assertEquals(0L, offset)
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd /Users/kbliu/Workspace/project/GostX/.worktrees/multi-config/android
./gradlew :app:testDebugUnitTest --tests "cn.liukebin.GostX.LogViewModelLogicTest" 2>&1 | grep -E "FAILED|ERROR|passed|failed" | tail -10
```

Expected: the three new tests fail.

- [ ] **Step 3: Update `LogRepository.kt`**

Replace the entire file content:

```kotlin
package cn.liukebin.GostX.data

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogRepository {
    @Volatile private var logFile: File? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (logFile != null) return
        synchronized(this) {
            if (logFile != null) return
            logFile = File(context.filesDir, "gostx.log")
        }
    }

    /** For tests only — bypasses Android Context. */
    internal fun initForTest(file: File) {
        logFile = file
    }

    fun getLogFile(): File =
        requireNotNull(logFile) { "LogRepository.init() must be called before use" }

    fun append(line: String) {
        val file = logFile ?: return
        try {
            synchronized(this) {
                val ts = timeFormat.format(Date())
                FileWriter(file, /* append = */ true).use { it.write("$ts $line\n") }
            }
        } catch (_: Exception) {
            // Silently ignore — logging must not affect VPN operation
        }
    }

    /** Truncates the log file to zero bytes. Keeps the file on disk so that
     *  any open file descriptors (e.g. Go's drain goroutine) remain valid;
     *  O_APPEND writers will resume from offset 0 after truncation. */
    fun deleteLog() {
        try {
            synchronized(this) {
                logFile?.writeText("")
            }
        } catch (_: Exception) {
            // Ignore — delete failure must not affect VPN operation
        }
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
cd /Users/kbliu/Workspace/project/GostX/.worktrees/multi-config/android
./gradlew :app:testDebugUnitTest --tests "cn.liukebin.GostX.LogViewModelLogicTest" 2>&1 | grep -E "FAILED|ERROR|passed|failed" | tail -10
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
cd /Users/kbliu/Workspace/project/GostX/.worktrees/multi-config
git add android/app/src/main/kotlin/cn/liukebin/GostX/data/LogRepository.kt \
        android/app/src/test/kotlin/cn/liukebin/GostX/LogViewModelLogicTest.kt
git commit -m "feat(android): auto-timestamp log lines; truncate instead of delete log

- LogRepository.append() prepends HH:mm:ss.SSS to every line automatically
- LogRepository.deleteLog() truncates to empty instead of deleting the file
  so Go's O_APPEND drain goroutine file descriptor stays valid after clear
- Add tests for timestamp format, truncate behaviour, and readFileFrom empty-truncation

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: GostVpnService — Wire up `setLogFile`, remove `vpnLogJob`

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt`

- [ ] **Step 1: Add `setLogFile` to `GostLibBridge` and call it from `onCreate()`**

In `GostVpnService.kt`, locate `GostLibBridge` object (around line 382). Add the new method after `setWorkDir`:

```kotlin
fun setLogFile(path: String): Result<Unit> =
    runCatching { invoke("setLogFile", path); Unit }
```

In `onCreate()` (around line 80), add after the `setWorkDir` call:

```kotlin
GostLibBridge.setLogFile(LogRepository.getLogFile().absolutePath)
    .onFailure { android.util.Log.e("GostX", "setLogFile failed: ${it.message}") }
```

The full updated `onCreate()` block should look like:

```kotlin
override fun onCreate() {
    super.onCreate()
    NotificationHelper.createChannel(this)
    LogRepository.init(this)
    configRepo = ConfigRepository(getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE))
    val workDir = getExternalFilesDir(null)?.absolutePath
        ?: filesDir.absolutePath.also { log("External storage unavailable, falling back to internal: $it") }
    GostLibBridge.setWorkDir(workDir)
        .onFailure { log("WARNING: setWorkDir($workDir) failed: ${it.message}") }
    GostLibBridge.setLogFile(LogRepository.getLogFile().absolutePath)
        .onFailure { android.util.Log.e("GostX", "setLogFile failed: ${it.message}") }
}
```

- [ ] **Step 2: Remove `vpnLogJob` field and `startVpnLogPolling()` method**

Remove the field declaration (line ~69):
```kotlin
@Volatile private var vpnLogJob: Job? = null
```

Remove the entire `startVpnLogPolling()` private method (lines ~241-254):
```kotlin
/** Polls the Go VPN log buffer every second and forwards entries to LogRepository. */
private fun startVpnLogPolling() {
    vpnLogJob?.cancel()
    vpnLogJob = scope.launch {
        while (isActive) {
            delay(1000)
            val msgs = GostLibBridge.getVPNLog()
            if (msgs.isNotEmpty()) {
                msgs.trimEnd('\n').split('\n').forEach { line ->
                    if (line.isNotEmpty()) LogRepository.append(line)
                }
            }
        }
    }
}
```

- [ ] **Step 3: Remove all `vpnLogJob` references from `startVpn()`, `stopVpn()`, `registerServiceReceiver()`, and `handleIdleModeChanged()`**

In `startVpn()` (around line 183), remove:
```kotlin
startVpnLogPolling()
```

In `stopVpn()` (around line 198-199), remove:
```kotlin
vpnLogJob?.cancel()
vpnLogJob = null
```

In `registerServiceReceiver()` BroadcastReceiver `when` block, remove the SCREEN_OFF and SCREEN_ON branches entirely:
```kotlin
Intent.ACTION_SCREEN_OFF -> {
    vpnLogJob?.cancel()
    vpnLogJob = null
}
Intent.ACTION_SCREEN_ON -> {
    if (GlobalVpnState.state.value.status == VpnStatus.CONNECTED &&
        vpnLogJob?.isActive != true
    ) {
        startVpnLogPolling()
    }
}
```

Also update the `IntentFilter` in `registerServiceReceiver()` — remove SCREEN_OFF and SCREEN_ON, guard for API level M, and simplify the receiver:

Replace the entire `registerServiceReceiver()` method with:

```kotlin
private fun registerServiceReceiver() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    unregisterServiceReceiver()
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) {
                handleIdleModeChanged()
            }
        }
    }
    registerReceiver(receiver, IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED))
    serviceReceiver = receiver
}
```

Replace `handleIdleModeChanged()` with the simplified version:

```kotlin
@RequiresApi(Build.VERSION_CODES.M)
private fun handleIdleModeChanged() {
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    if (pm.isDeviceIdleMode) {
        unregisterNetworkCallback()
    } else {
        registerNetworkCallback()
    }
}
```

- [ ] **Step 4: Remove unused imports**

Remove from the import block:
```kotlin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
```

Verify `android.util.Log` is not already imported (the `setLogFile` failure logging uses it). If not present, it was added via the fully-qualified `android.util.Log.e(...)` call so no import needed.

- [ ] **Step 5: Remove `getVPNLog()` from `GostLibBridge` (dead code)**

In `GostLibBridge` object, remove:
```kotlin
fun getVPNLog(): String = invoke("getVPNLog") as? String ?: ""
```

- [ ] **Step 6: Build Android project to confirm no compilation errors**

```bash
cd /Users/kbliu/Workspace/project/GostX/.worktrees/multi-config/android
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|warning:|BUILD" | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Run unit tests**

```bash
cd /Users/kbliu/Workspace/project/GostX/.worktrees/multi-config/android
./gradlew :app:testDebugUnitTest 2>&1 | grep -E "FAILED|ERROR|passed|failed" | tail -10
```

Expected: all tests pass.

- [ ] **Step 8: Commit**

```bash
cd /Users/kbliu/Workspace/project/GostX/.worktrees/multi-config
git add android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt
git commit -m "feat(android): wire up Go setLogFile; remove Kotlin vpnLogJob polling

- Call GostLibBridge.setLogFile() in onCreate() so Go drain goroutine writes
  VPN logs directly to file from process start
- Remove vpnLogJob field and startVpnLogPolling() method entirely
- Simplify registerServiceReceiver(): only DEVICE_IDLE_MODE_CHANGED remains
  (SCREEN_OFF/ON were only used to start/stop vpnLogJob)
- Simplify handleIdleModeChanged(): only manages networkCallback, no log jobs
- Remove dead GostLibBridge.getVPNLog() method

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: GostVpnService — Milestone logs for start/stop timing

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt`

- [ ] **Step 1: Add milestone logs to `startVpn()`**

The goal is to log before and after each blocking call so timestamps (added by `LogRepository.append()`) reveal where time is spent.

Find `startVpn()` and update it so it matches (only the log lines are new; all other code is unchanged):

```kotlin
private fun startVpn() {
    GlobalVpnState.setConnecting()
    val yaml = configRepo.getActiveConfig()

    val vpnDnsAddr: String
    try {
        log("[start] loading config and starting gost...")
        GostLibBridge.startVPNMode(yaml)
        vpnDnsAddr = GostLibBridge.getVpnDnsAddr()
        log("[start] gost ready")
    } catch (e: Exception) {
        log("gost start failed: ${e.message}")
        GlobalVpnState.setError("gost 启动失败: ${e.message}")
        stopSelf()
        return
    }
    val builder = Builder()
        .setMtu(1500)
        .addAddress("10.0.0.2", 24)
        .addRoute("0.0.0.0", 0)
        .addDnsServer(if (vpnDnsAddr.isNotEmpty()) vpnDnsAddr else "8.8.8.8")
        .setSession("GostX")
        .setBlocking(false)
        .addDisallowedApplication(packageName)

    log("[start] establishing VPN interface...")
    tunFd = builder.establish() ?: run {
        log("Failed to establish VPN interface")
        GlobalVpnState.setError("VPN 接口建立失败，请先授予 VPN 权限")
        GostLibBridge.stop()
        stopSelf()
        return
    }
    log("[start] VPN interface ready")

    try {
        log("[start] starting tun2socks...")
        GostLibBridge.startVPN(tunFd!!.fd.toLong(), 1500L)
    } catch (e: Exception) {
        log("tun2socks start failed: ${e.message}")
        GlobalVpnState.setError("tun2socks 启动失败: ${e.message}")
        closeTun()
        GostLibBridge.stop()
        stopSelf()
        return
    }

    try {
        val status = GostLibBridge.getStatus()
        val addr = parseFirstAddress(status)
        GlobalVpnState.setConnected(addr)
        lastVpnConnectTime = System.currentTimeMillis()
        promoteToForeground(addr)
        log("[start] VPN connected, addr: $addr")
        registerNetworkCallback()
        saveLastRunState(true)
        GostLibBridge.setMemoryLimit(true)
        registerServiceReceiver()
    } catch (e: Exception) {
        log("VPN post-start error: ${e.message}")
        GlobalVpnState.setError("VPN 启动后错误: ${e.message}")
        GostLibBridge.setMemoryLimit(false)
        closeTun()
        GostLibBridge.stopVPN()
        GostLibBridge.stop()
        stopSelf()
    }
}
```

- [ ] **Step 2: Add milestone logs to `stopVpn()`**

Update `stopVpn()` to add three milestone logs. The only change is adding the three `log(...)` lines — all other code is unchanged:

```kotlin
private fun stopVpn(updatePersistentState: Boolean = true) {
    if (updatePersistentState) GlobalVpnState.setStopping()
    unregisterNetworkCallback()
    closeTun()
    runCatching { unregisterServiceReceiver() }

    // Go cleanup: Stop() has a 5-second timeout on serveWg.Wait(), so this
    // call is guaranteed to return within 5 seconds. STOPPING loading is shown
    // during this time, preventing the user from starting a new connection while
    // the previous Go shutdown is still in progress.
    GostLibBridge.setMemoryLimit(false)
    log("[stop] stopping tun2socks...")
    GostLibBridge.stopVPN()
    log("[stop] stopping gost...")
    GostLibBridge.stop()

    if (updatePersistentState) {
        GlobalVpnState.setStopped()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        saveLastRunState(false)
        log("[stop] VPN stopped")
    } else {
        GlobalVpnState.setConnecting()
        log("VPN restarting after network change")
    }
}
```

- [ ] **Step 3: Build to confirm no compilation errors**

```bash
cd /Users/kbliu/Workspace/project/GostX/.worktrees/multi-config/android
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD" | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
cd /Users/kbliu/Workspace/project/GostX/.worktrees/multi-config
git add android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt
git commit -m "feat(android): add milestone logs to startVpn/stopVpn for timing visibility

Each blocking step (gost start, VPN interface, tun2socks, stopVPN, stop) is
now bracketed by [start]/[stop] log lines. Combined with the automatic
HH:mm:ss.SSS timestamp from LogRepository, the log screen immediately shows
where time is spent during connect/disconnect.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: LogViewModel — Always poll; add `setFollowing()`

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/log/LogViewModel.kt`
- Modify: `android/app/src/test/kotlin/cn/liukebin/GostX/LogViewModelLogicTest.kt`

- [ ] **Step 1: Add compile-time API check for `setFollowing` to the test file**

In `LogViewModelLogicTest.kt`, find the bottom of the file where the existing compile-time check lives:

```kotlin
// Compile-time check: startPolling and stopPolling must be public
private fun _assertLogViewModelPollingApi(vm: cn.liukebin.GostX.ui.log.LogViewModel) {
    vm.startPolling()
    vm.stopPolling()
}
```

Replace it with:

```kotlin
// Compile-time check: public polling and follow API
private fun _assertLogViewModelApi(vm: cn.liukebin.GostX.ui.log.LogViewModel) {
    vm.startPolling()
    vm.stopPolling()
    vm.setFollowing(true)
    vm.setFollowing(false)
}
```

- [ ] **Step 2: Run tests to confirm compile-time check fails (undefined `setFollowing`)**

```bash
cd /Users/kbliu/Workspace/project/GostX/.worktrees/multi-config/android
./gradlew :app:testDebugUnitTest --tests "cn.liukebin.GostX.LogViewModelLogicTest" 2>&1 | grep -E "error:|unresolved" | head -5
```

Expected: compile error mentioning `setFollowing`

- [ ] **Step 3: Update `LogViewModel.kt`**

Replace the full file with:

```kotlin
package cn.liukebin.GostX.ui.log

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import cn.liukebin.GostX.data.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Reads bytes from [file] starting at [offset].
 * Returns a (newLines, newOffset) pair. newOffset equals file length after the read,
 * or 0 if the file does not exist.
 * If the file was truncated (length < offset), re-reads from the beginning.
 */
internal fun readFileFrom(file: File, offset: Long): Pair<List<String>, Long> {
    return try {
        if (!file.exists()) return Pair(emptyList(), 0L)
        val fileLen = file.length()
        when {
            fileLen < offset -> {
                // File was truncated; re-read from start
                val bytes = file.inputStream().use { it.readBytes() }
                Pair(String(bytes).lines().filter { it.isNotEmpty() }, fileLen)
            }
            fileLen == offset -> Pair(emptyList(), offset)
            else -> {
                val newBytes = file.inputStream().use { stream ->
                    stream.skip(offset)
                    stream.readBytes()
                }
                Pair(String(newBytes).lines().filter { it.isNotEmpty() }, fileLen)
            }
        }
    } catch (_: Exception) {
        Pair(emptyList(), 0L)
    }
}

class LogViewModel(
    application: Application,
    private val logFile: File,
) : AndroidViewModel(application) {

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.AndroidViewModelFactory() {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras,
            ): T {
                val app = checkNotNull(extras[APPLICATION_KEY])
                LogRepository.init(app)
                return LogViewModel(app, LogRepository.getLogFile()) as T
            }
        }
    }

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val _isFollowing = MutableStateFlow(true)
    val isFollowing: StateFlow<Boolean> = _isFollowing.asStateFlow()

    private var fileOffset: Long = 0L
    private val readMutex = Mutex()
    @Volatile private var pollJob: Job? = null

    /** Loads existing log file content into [lines]. Call before [startPolling]. */
    fun loadInitial() {
        viewModelScope.launch(Dispatchers.IO) {
            readMutex.withLock {
                val (lines, offset) = readFileFrom(logFile, 0L)
                _lines.value = lines.takeLast(2000)
                fileOffset = offset
            }
        }
    }

    /** Starts periodic polling. New lines are always appended regardless of
     *  [isFollowing]; [isFollowing] controls only whether the view auto-scrolls. */
    fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000)
                appendNewLines()
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun appendNewLines() {
        readMutex.withLock {
            if (!logFile.exists()) {
                if (_lines.value.isNotEmpty()) {
                    _lines.value = emptyList()
                    fileOffset = 0L
                }
                return
            }
            val (newLines, newOffset) = readFileFrom(logFile, fileOffset)
            fileOffset = newOffset
            if (newLines.isNotEmpty()) {
                _lines.value = (_lines.value + newLines).takeLast(2000)
            }
        }
    }

    /** Sets live-tail mode. When enabling, immediately reads missed lines so
     *  the view is current without waiting for the next polling tick. */
    fun setFollowing(value: Boolean) {
        _isFollowing.value = value
        if (value) viewModelScope.launch(Dispatchers.IO) { appendNewLines() }
    }

    /** Toggles live-tail mode. */
    fun toggleFollow() = setFollowing(!_isFollowing.value)

    /** Returns full file contents for clipboard copy. Does not depend on in-memory [lines]. */
    fun copyAll(): String = try { logFile.readText() } catch (_: Exception) { "" }

    /** Truncates the log file and clears the displayed lines. */
    fun clearLog() {
        viewModelScope.launch(Dispatchers.IO) {
            readMutex.withLock {
                LogRepository.deleteLog()
                _lines.value = emptyList()
                fileOffset = 0L
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
cd /Users/kbliu/Workspace/project/GostX/.worktrees/multi-config/android
./gradlew :app:testDebugUnitTest --tests "cn.liukebin.GostX.LogViewModelLogicTest" 2>&1 | grep -E "FAILED|ERROR|passed|failed" | tail -10
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
cd /Users/kbliu/Workspace/project/GostX/.worktrees/multi-config
git add android/app/src/main/kotlin/cn/liukebin/GostX/ui/log/LogViewModel.kt \
        android/app/src/test/kotlin/cn/liukebin/GostX/LogViewModelLogicTest.kt
git commit -m "feat(android): LogViewModel always polls; isFollowing = auto-scroll only

- startPolling() always calls appendNewLines() regardless of isFollowing
  so _lines stays current while user browses history
- Add setFollowing(Boolean): sets flag and immediately reads missed lines on enable
- toggleFollow() delegates to setFollowing for consistency
- Update compile-time API check to include setFollowing

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: LogScreen — Auto-pause follow on scroll

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/log/LogScreen.kt`

- [ ] **Step 1: Add `snapshotFlow` import and the auto-pause effect**

In `LogScreen.kt`, add the import:

```kotlin
import androidx.compose.runtime.snapshotFlow
```

After the existing `LaunchedEffect(lines.size, isFollowing)` block (around line 64), add:

```kotlin
// Auto-pause follow when the user scrolls away from the bottom.
// Fires when a scroll gesture settles. animateScrollToItem (triggered by
// isFollowing=true) also settles — but always at the last item, so the
// `last < totalItemsCount - 1` check prevents disabling follow in that case.
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

- [ ] **Step 2: Build to confirm no compilation errors**

```bash
cd /Users/kbliu/Workspace/project/GostX/.worktrees/multi-config/android
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|BUILD" | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Run all unit tests**

```bash
cd /Users/kbliu/Workspace/project/GostX/.worktrees/multi-config/android
./gradlew :app:testDebugUnitTest 2>&1 | grep -E "FAILED|ERROR|passed|failed" | tail -10
```

Expected: all tests pass.

- [ ] **Step 4: Commit**

```bash
cd /Users/kbliu/Workspace/project/GostX/.worktrees/multi-config
git add android/app/src/main/kotlin/cn/liukebin/GostX/ui/log/LogScreen.kt
git commit -m "feat(android): auto-pause log follow when user scrolls away from bottom

Uses snapshotFlow on LazyListState.isScrollInProgress to detect when a
scroll gesture settles outside the last item. Only disables following
when user has scrolled up; programmatic animateScrollToItem always lands
at the last item so it does not trigger this guard.
Re-enabling follow requires the user to tap the play button in the top bar.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```
