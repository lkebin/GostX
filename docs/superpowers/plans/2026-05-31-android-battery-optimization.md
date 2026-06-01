# Android Battery Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce background power consumption on Android by making log polling screen-aware, adding Doze mode support, constraining Go runtime memory, and enabling relative file paths from external storage.

**Architecture:** Five independent changes across the Go library layer (`go/gostlib/`) and the Android service/UI layer (`android/`). No JNI interface restructuring — only new exported functions added to the Go side.

**Tech Stack:** Kotlin, Jetpack Compose, Android VpnService, Go `runtime/debug`, `kotlinx-coroutines-test:1.7.3`, JUnit 4

---

## File Map

| File | What changes |
|------|-------------|
| `go/gostlib/gostlib.go` | Add `SetMemoryLimit` + `SetWorkDir` |
| `go/gostlib/gostlib_test.go` | Add `TestSetWorkDir` |
| `android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt` | Add `serviceReceiver`, screen/Doze handling, memory limit calls, workdir call |
| `android/app/src/main/kotlin/cn/liukebin/GostX/ui/log/LogViewModel.kt` | Make `startPolling` public, add `stopPolling`, remove auto-start from `loadInitial` |
| `android/app/src/main/kotlin/cn/liukebin/GostX/ui/log/LogScreen.kt` | Replace `LaunchedEffect` with `DisposableEffect` for polling lifecycle |
| `android/app/src/test/kotlin/cn/liukebin/GostX/LogViewModelLogicTest.kt` | Add polling control tests |

---

## Task 1: Go — `SetMemoryLimit` and `SetWorkDir`

**Files:**
- Modify: `go/gostlib/gostlib.go`
- Modify: `go/gostlib/gostlib_test.go`

- [ ] **Step 1: Add imports to `gostlib.go`**

At the top of the import block in `go/gostlib/gostlib.go`, add the three new imports:

```go
import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"net"
	"os"
	runtimeDebug "runtime/debug"
	"sync"
	"sync/atomic"

	"github.com/go-gost/core/service"
	// ... rest unchanged
```

- [ ] **Step 2: Add `SetMemoryLimit` and `SetWorkDir` functions to `gostlib.go`**

Append these two functions at the end of `go/gostlib/gostlib.go` (after the existing `injectInternalSocks5` function):

```go
// SetMemoryLimit configures the Go runtime GC for mobile background use.
// enabled=true: aggressive GC (GOGC=10) + 30 MB soft heap limit to prevent
// unbounded growth during high-traffic VPN sessions.
// enabled=false: restore defaults so normal service mode is unaffected.
// Call with enabled=true when VPN starts, false when it stops.
func SetMemoryLimit(enabled bool) {
	const limit = 30 * 1024 * 1024
	if enabled {
		runtimeDebug.SetGCPercent(10)
		runtimeDebug.SetMemoryLimit(limit)
	} else {
		runtimeDebug.SetGCPercent(100)
		runtimeDebug.SetMemoryLimit(math.MaxInt64)
	}
}

// SetWorkDir sets the process working directory so that relative file paths
// in gost configs (e.g. bypass.file.path: china_ip_list.txt) resolve against
// the given directory. Should be called once at service creation time with
// the app's external files directory.
func SetWorkDir(path string) error {
	return os.Chdir(path)
}
```

- [ ] **Step 3: Write the failing test for `SetWorkDir`**

Add to `go/gostlib/gostlib_test.go`. First add the new imports at the top:

```go
import (
	"context"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	coreservice "github.com/go-gost/core/service"
)
```

Then append the test function:

```go
func TestSetWorkDir(t *testing.T) {
	orig, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = os.Chdir(orig) }()

	tmp, err := os.MkdirTemp("", "workdir_test")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmp)

	if err := SetWorkDir(tmp); err != nil {
		t.Fatalf("SetWorkDir(%q) failed: %v", tmp, err)
	}

	got, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	// Resolve symlinks: macOS /var -> /private/var
	wantResolved, _ := filepath.EvalSymlinks(tmp)
	gotResolved, _ := filepath.EvalSymlinks(got)
	if wantResolved != gotResolved {
		t.Errorf("after SetWorkDir: Getwd() = %q, want %q", gotResolved, wantResolved)
	}
}
```

- [ ] **Step 4: Run test to verify it fails (function not yet implemented)**

```bash
cd go && go test ./gostlib/... -run TestSetWorkDir -v
```

Expected: `FAIL` — `SetWorkDir` undefined.

- [ ] **Step 5: Run all Go tests to check baseline**

```bash
cd go && go test ./gostlib/... -v
```

Expected: all existing tests pass. Note the output for comparison after implementation.

- [ ] **Step 6: Verify the new functions compile**

```bash
cd go && go build ./gostlib/...
```

Expected: builds cleanly (imports now present, functions defined).

- [ ] **Step 7: Run the test again to verify it passes**

```bash
cd go && go test ./gostlib/... -run TestSetWorkDir -v
```

Expected: `PASS`.

- [ ] **Step 8: Run all Go tests to confirm no regressions**

```bash
cd go && go test ./gostlib/... -v
```

Expected: all tests pass.

- [ ] **Step 9: Commit**

```bash
git add go/gostlib/gostlib.go go/gostlib/gostlib_test.go
git commit -m "feat(go): add SetMemoryLimit and SetWorkDir to gostlib"
```

---

## Task 2: Android — `GostLibBridge` additions + `onCreate` workdir

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt`

`GostLibBridge` lives at the bottom of `GostVpnService.kt`. All changes in this task are to that object and the `onCreate` override.

- [ ] **Step 1: Add `setMemoryLimit` and `setWorkDir` to `GostLibBridge`**

In `GostVpnService.kt`, find the `GostLibBridge` object (line ~299). Append these two functions after the existing `fun validateConfig(...)` function:

```kotlin
    fun setMemoryLimit(enabled: Boolean) {
        runCatching { invoke("setMemoryLimit", enabled) }
    }

    fun setWorkDir(path: String) {
        runCatching { invoke("setWorkDir", path) }
    }
```

- [ ] **Step 2: Call `setWorkDir` in `GostVpnService.onCreate`**

Find the existing `onCreate()` override (line ~75):

```kotlin
override fun onCreate() {
    super.onCreate()
    NotificationHelper.createChannel(this)
    LogRepository.init(this)
    configRepo = ConfigRepository(getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE))
}
```

Replace with:

```kotlin
override fun onCreate() {
    super.onCreate()
    NotificationHelper.createChannel(this)
    LogRepository.init(this)
    configRepo = ConfigRepository(getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE))
    val workDir = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
    GostLibBridge.setWorkDir(workDir)
}
```

- [ ] **Step 3: Run Android unit tests to confirm baseline**

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

Expected: all existing tests pass.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt
git commit -m "feat(android): add GostLibBridge setMemoryLimit/setWorkDir; set workdir in onCreate"
```

---

## Task 3: Android — Service receiver (screen + Doze) + memory limit calls

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt`

- [ ] **Step 1: Add missing imports**

At the top of `GostVpnService.kt`, add the following imports (insert after the existing `android.os.*` imports):

```kotlin
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.PowerManager
import androidx.annotation.RequiresApi
```

- [ ] **Step 2: Add `serviceReceiver` field to `GostVpnService`**

In the `GostVpnService` class body, after the existing field declarations (after `private val RECONNECT_COOLDOWN_MS = 30_000L`), add:

```kotlin
    private var serviceReceiver: BroadcastReceiver? = null
```

- [ ] **Step 3: Add `registerServiceReceiver`, `unregisterServiceReceiver`, and `handleIdleModeChanged`**

Add these three methods to `GostVpnService`, before the existing `registerNetworkCallback()` method:

```kotlin
    private fun registerServiceReceiver() {
        unregisterServiceReceiver() // prevent double-registration on reconnect
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
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
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            handleIdleModeChanged()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }
        }
        registerReceiver(receiver, filter)
        serviceReceiver = receiver
    }

    private fun unregisterServiceReceiver() {
        serviceReceiver?.let { unregisterReceiver(it) }
        serviceReceiver = null
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun handleIdleModeChanged() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isDeviceIdleMode) {
            // Entering Doze: stop log polling and network callback to avoid
            // spurious reconnect attempts while the device is deeply sleeping.
            vpnLogJob?.cancel()
            vpnLogJob = null
            unregisterNetworkCallback()
        } else {
            // Exiting Doze: restore network monitoring and resume log polling
            // only if the screen is also on (isInteractive).
            registerNetworkCallback()
            if (pm.isInteractive &&
                GlobalVpnState.state.value.status == VpnStatus.CONNECTED &&
                vpnLogJob?.isActive != true
            ) {
                startVpnLogPolling()
            }
        }
    }
```

- [ ] **Step 4: Call `registerServiceReceiver` and `setMemoryLimit(true)` at end of `startVpn`**

Find the successful-exit block of `startVpn()` — the `try` block that ends with `startVpnLogPolling()`:

```kotlin
        try {
            val status = GostLibBridge.getStatus()
            val addr = parseFirstAddress(status)
            GlobalVpnState.setConnected(addr)
            lastVpnConnectTime = System.currentTimeMillis()
            promoteToForeground(addr)
            log("VPN started, gost status: $status")
            registerNetworkCallback()
            saveLastRunState(true)
            startVpnLogPolling()
        } catch (e: Exception) {
```

Replace with:

```kotlin
        try {
            val status = GostLibBridge.getStatus()
            val addr = parseFirstAddress(status)
            GlobalVpnState.setConnected(addr)
            lastVpnConnectTime = System.currentTimeMillis()
            promoteToForeground(addr)
            log("VPN started, gost status: $status")
            registerNetworkCallback()
            saveLastRunState(true)
            GostLibBridge.setMemoryLimit(true)
            registerServiceReceiver()
            startVpnLogPolling()
        } catch (e: Exception) {
```

- [ ] **Step 5: Call `unregisterServiceReceiver` and `setMemoryLimit(false)` in `stopVpn`**

Find `stopVpn`. The `if (updatePersistentState)` block that calls `stopSelf()` ends with `log("VPN stopped")`. Replace that whole `if/else` with:

```kotlin
        GostLibBridge.setMemoryLimit(false)
        unregisterServiceReceiver()
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
            log("VPN stopped")
        } else {
            // Reconnect path: re-register service receiver for the new session
            registerServiceReceiver()
            GlobalVpnState.setConnecting()
            log("VPN restarting after network change")
        }
```

Note: `setMemoryLimit(false)` + `unregisterServiceReceiver()` happen unconditionally (both stop and reconnect paths), but the reconnect path immediately re-registers the receiver. `setMemoryLimit(true)` is called again at the end of the subsequent `startVpn()`.

- [ ] **Step 6: Run Android unit tests**

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt
git commit -m "feat(android): screen-aware log polling, Doze mode support, Go memory limit"
```

---

## Task 4: Android — `LogViewModel` polling lifecycle refactor

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/log/LogViewModel.kt`
- Modify: `android/app/src/test/kotlin/cn/liukebin/GostX/LogViewModelLogicTest.kt`

- [ ] **Step 1: Write a failing test for `stopPolling` existence**

In `LogViewModelLogicTest.kt`, add this import and test at the end of the file to confirm `stopPolling` doesn't yet exist as a public function (it will fail to compile):

```kotlin
import cn.liukebin.GostX.ui.log.LogViewModel
import java.io.File

// Compile-time check: stopPolling must be public
private fun _assertLogViewModelPollingApi(vm: LogViewModel) {
    vm.startPolling()
    vm.stopPolling()
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
cd android && ./gradlew :app:testDebugUnitTest 2>&1 | grep -E "error|FAILED|stopPolling"
```

Expected: compile error — `startPolling` is private and `stopPolling` is not defined.

- [ ] **Step 3: Refactor `LogViewModel.kt`**

Make the following three changes to `LogViewModel.kt`:

**a) Make `startPolling` public** — change the function signature from:
```kotlin
    private fun startPolling() {
```
to:
```kotlin
    fun startPolling() {
```

**b) Add `stopPolling`** — insert after the `startPolling` function:
```kotlin
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }
```

**c) Remove the `startPolling()` call from `loadInitial`** — find the `loadInitial` function:
```kotlin
    fun loadInitial() {
        viewModelScope.launch(Dispatchers.IO) {
            readMutex.withLock {
                val (lines, offset) = readFileFrom(logFile, 0L)
                _lines.value = lines.takeLast(2000)
                fileOffset = offset
            }
            startPolling()
        }
    }
```

Replace with (remove only the `startPolling()` line):
```kotlin
    fun loadInitial() {
        viewModelScope.launch(Dispatchers.IO) {
            readMutex.withLock {
                val (lines, offset) = readFileFrom(logFile, 0L)
                _lines.value = lines.takeLast(2000)
                fileOffset = offset
            }
        }
    }
```

- [ ] **Step 4: Run unit tests to confirm compile check passes and no regressions**

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/ui/log/LogViewModel.kt \
        android/app/src/test/kotlin/cn/liukebin/GostX/LogViewModelLogicTest.kt
git commit -m "feat(android): LogViewModel polling driven by lifecycle, not loadInitial"
```

---

## Task 5: Android — `LogScreen` composable polling lifecycle

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/log/LogScreen.kt`

- [ ] **Step 1: Add the `DisposableEffect` import**

In `LogScreen.kt`, the imports currently include:
```kotlin
import androidx.compose.runtime.LaunchedEffect
```

Add the following import alongside it:
```kotlin
import androidx.compose.runtime.DisposableEffect
```

- [ ] **Step 2: Replace `LaunchedEffect` with `DisposableEffect`**

Find the existing effect block (lines ~51–53):

```kotlin
    LaunchedEffect(Unit) {
        viewModel.loadInitial()
    }
```

Replace with:

```kotlin
    DisposableEffect(Unit) {
        viewModel.loadInitial()
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }
```

`loadInitial()` and `startPolling()` are non-suspending (they launch coroutines internally via `viewModelScope`), so calling them in `DisposableEffect` is correct. `onDispose` fires when the composable leaves the composition, stopping the poll loop.

- [ ] **Step 3: Run Android unit tests**

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

Expected: all tests pass.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/ui/log/LogScreen.kt
git commit -m "feat(android): stop log polling when leaving log screen"
```

---

## Manual Verification Checklist

After all tasks are complete and the AAR is rebuilt:

| Check | How |
|-------|-----|
| Log polling stops when screen off | Connect VPN, lock device, watch logcat — no `getVPNLog` calls after ~1s |
| Log polling resumes when screen on | Unlock device — log entries resume within 1s |
| Log polling stops on leaving log page | Open log page, navigate away, confirm no polling in logcat |
| Log polling starts on entering log page | Navigate back to log page, confirm polling resumes |
| Doze mode | `adb shell dumpsys deviceidle force-idle` → VPN stays connected, log polling stops; `adb shell dumpsys deviceidle unforce` → polling resumes |
| Memory limit | Run with many connections, Android Studio Profiler → native heap stays near 30 MB ceiling instead of growing unbounded |
| External file path | Place a file in `/storage/emulated/0/Android/data/cn.liukebin.GostX/files/test.txt`, reference it as `path: test.txt` in gost bypass config, confirm gost loads it |

---

## Build Note

After completing all tasks, rebuild the Go AAR before testing on device:

```bash
cd go && make gostlib.aar
cp gostlib.aar gostlib-sources.jar ../android/app/libs/
```
