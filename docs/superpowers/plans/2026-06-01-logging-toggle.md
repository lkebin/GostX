# Logging Toggle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a 「日志记录」toggle (default off) that completely eliminates log I/O when disabled; hide the log entry on the home screen when disabled.

**Architecture:** Go layer gets `SetLoggingEnabled(bool)` + atomic guard in `logVPN()`. Kotlin layer stores the preference in `ConfigRepository` and passes it to Go on VPN start. A new `SettingsScreen` hosts the toggle. HomeScreen's log icon is conditionally shown.

**Tech Stack:** Kotlin/Compose, Go/gomobile, SharedPreferences, `atomic.Bool`

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `go/gostlib/vpnlog.go` | Modify | Add `loggingEnabled atomic.Bool`, guard in `logVPN()`, export `SetLoggingEnabled()` |
| `go/gostlib/gostlib_test.go` | Modify | Add `TestSetLoggingEnabled` |
| `android/app/libs/gostlib.aar` | Rebuild | Expose new `SetLoggingEnabled` JNI method |
| `android/app/src/main/kotlin/.../data/ConfigRepository.kt` | Modify | Add `loggingEnabled` get/set + `loggingEnabledFlow` |
| `android/app/src/main/kotlin/.../service/GostVpnService.kt` | Modify | Call `setLoggingEnabled()` + conditional `setLogFile()` in `startVpn()` |
| `android/app/src/main/kotlin/.../ui/Navigation.kt` | Modify | Add `Screen.Settings` |
| `android/app/src/main/kotlin/.../ui/settings/SettingsScreen.kt` | Create | Settings UI with 日志记录 Switch |
| `android/app/src/main/kotlin/.../ui/home/HomeViewModel.kt` | Modify | Expose `loggingEnabledFlow` from ConfigRepository |
| `android/app/src/main/kotlin/.../ui/home/HomeScreen.kt` | Modify | Conditional log icon + settings icon |
| `android/app/src/main/kotlin/cn/liukebin/GostX/MainActivity.kt` | Modify | Register Settings route in NavHost |
| `android/app/src/main/res/values/strings.xml` | Modify | Add settings strings |

---

## Task 1: Go — add `SetLoggingEnabled` with test

**Files:**
- Modify: `go/gostlib/vpnlog.go`
- Modify: `go/gostlib/gostlib_test.go`

- [ ] **Step 1: Write the failing test**

In `go/gostlib/gostlib_test.go`, add after `TestSetLogFile`:

```go
func TestSetLoggingEnabled(t *testing.T) {
	resetLogDrainForTest()
	t.Cleanup(func() {
		SetLoggingEnabled(true) // restore default for other tests
		resetLogDrainForTest()
	})

	// With logging disabled, logVPN should not enqueue anything.
	SetLoggingEnabled(false)
	logVPN("should not appear")
	select {
	case msg := <-vpnLogCh:
		t.Fatalf("expected no message when logging disabled, got: %q", msg)
	default:
		// correct — channel empty
	}

	// With logging enabled, logVPN should enqueue.
	SetLoggingEnabled(true)
	logVPN("should appear")
	select {
	case msg := <-vpnLogCh:
		if !strings.HasSuffix(msg, "should appear") {
			t.Errorf("unexpected message: %q", msg)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatal("expected message not received within 500ms")
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd go && go test ./gostlib/ -run TestSetLoggingEnabled -v
```
Expected: FAIL with `SetLoggingEnabled: undefined`

- [ ] **Step 3: Add `loggingEnabled` + `SetLoggingEnabled` + guard in `logVPN()`**

In `go/gostlib/vpnlog.go`, after the `var vpnLogCh` line add:

```go
// loggingEnabled gates all logVPN output. False by default; call SetLoggingEnabled(true)
// before starting VPN to activate logging.
var loggingEnabled atomic.Bool
```

Replace `logVPN`:

```go
func logVPN(format string, args ...any) {
	if !loggingEnabled.Load() {
		return
	}
	ts := time.Now().Format("15:04:05.000")
	msg := ts + " " + fmt.Sprintf(format, args...)
	select {
	case vpnLogCh <- msg:
	default:
		// buffer full – drop to avoid blocking the packet-dispatch goroutine
	}
}
```

Add after `GetVPNLog`:

```go
// SetLoggingEnabled enables or disables VPN log output. Must be called before
// starting the VPN for the setting to take effect on that session.
func SetLoggingEnabled(v bool) { loggingEnabled.Store(v) }
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd go && go test ./gostlib/ -run TestSetLoggingEnabled -v
```
Expected: PASS

- [ ] **Step 5: Run all Go tests**

```bash
cd go && go test ./gostlib/ -v 2>&1 | tail -20
```
Expected: all PASS

- [ ] **Step 6: Rebuild AAR**

```bash
cd go && make gostlib.aar
```
Expected: builds successfully, produces `android/app/libs/gostlib.aar`

- [ ] **Step 7: Commit**

```bash
cd go && git add gostlib/vpnlog.go gostlib/gostlib_test.go
git add -f android/app/libs/gostlib.aar android/app/libs/gostlib-sources.jar
git commit -m "feat(go): add SetLoggingEnabled; logVPN is no-op when disabled"
```

---

## Task 2: ConfigRepository — loggingEnabled preference

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/data/ConfigRepository.kt`

- [ ] **Step 1: Add constant and StateFlow**

At the top of `ConfigRepository.kt`, after the existing `private const` lines, add:

```kotlin
private const val KEY_LOGGING_ENABLED = "logging_enabled"
```

Inside `class ConfigRepository`, after the `_activeProfileIdFlow` declaration, add:

```kotlin
private val _loggingEnabledFlow = MutableStateFlow(
    prefs.getBoolean(KEY_LOGGING_ENABLED, false)
)
val loggingEnabledFlow: StateFlow<Boolean> = _loggingEnabledFlow.asStateFlow()

var loggingEnabled: Boolean
    get() = _loggingEnabledFlow.value
    set(value) {
        prefs.edit().putBoolean(KEY_LOGGING_ENABLED, value).apply()
        _loggingEnabledFlow.value = value
    }
```

- [ ] **Step 2: Run unit tests to verify no regression**

```bash
cd android && ./gradlew testDebugUnitTest 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/data/ConfigRepository.kt
git commit -m "feat(data): add loggingEnabled preference to ConfigRepository"
```

---

## Task 3: GostVpnService — pass loggingEnabled to Go on start

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt`

- [ ] **Step 1: Add `setLoggingEnabled` to `GostLibBridge`**

In `GostVpnService.kt`, inside `internal object GostLibBridge`, add after `setLogFile`:

```kotlin
fun setLoggingEnabled(enabled: Boolean) {
    runCatching { invoke("setLoggingEnabled", enabled) }
}
```

- [ ] **Step 2: Call it in `startVpn()`**

In `GostVpnService.kt`, replace the `onCreate()` block that calls `setLogFile`:

```kotlin
// SetLogFile uses sync.Once on the Go side — idempotent on service restart.
GostLibBridge.setLogFile(LogRepository.getLogFile().absolutePath)
    .onFailure { log("WARNING: setLogFile failed: ${it.message}") }
```

with:

```kotlin
val loggingOn = configRepo.loggingEnabled
GostLibBridge.setLoggingEnabled(loggingOn)
if (loggingOn) {
    GostLibBridge.setLogFile(LogRepository.getLogFile().absolutePath)
        .onFailure { log("WARNING: setLogFile failed: ${it.message}") }
}
```

> Note: This moves the setLogFile call from `onCreate` to startVpn logic. Remove the two lines from `onCreate` and add the block above inside `startVpn()`, just before `log("[start] loading config and starting gost...")`.

- [ ] **Step 3: Run unit tests**

```bash
cd android && ./gradlew testDebugUnitTest 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt
git commit -m "feat(service): pass loggingEnabled to Go on VPN start"
```

---

## Task 4: SettingsScreen — new UI

**Files:**
- Create: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/settings/SettingsScreen.kt`
- Modify: `android/app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add strings**

In `strings.xml`, before `</resources>`:

```xml
<!-- Settings screen -->
<string name="nav_settings">Settings</string>
<string name="settings_title">设置</string>
<string name="settings_logging_label">日志记录</string>
<string name="settings_logging_restart_hint">关闭后需重启 VPN 生效</string>
```

- [ ] **Step 2: Create SettingsScreen**

Create `android/app/src/main/kotlin/cn/liukebin/GostX/ui/settings/SettingsScreen.kt`:

```kotlin
package cn.liukebin.GostX.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.liukebin.GostX.R
import cn.liukebin.GostX.data.ConfigRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repo: ConfigRepository,
    onBack: () -> Unit = {},
    vm: SettingsViewModel = viewModel(
        factory = remember(repo) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras
                ): T = SettingsViewModel(repo) as T
            }
        }
    )
) {
    val loggingEnabled by vm.loggingEnabled.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_logging_label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.settings_logging_restart_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.padding(end = 8.dp))
                Switch(
                    checked = loggingEnabled,
                    onCheckedChange = { vm.setLoggingEnabled(it) }
                )
            }
        }
    }
}
```

- [ ] **Step 3: Create SettingsViewModel**

Create `android/app/src/main/kotlin/cn/liukebin/GostX/ui/settings/SettingsViewModel.kt`:

```kotlin
package cn.liukebin.GostX.ui.settings

import androidx.lifecycle.ViewModel
import cn.liukebin.GostX.data.ConfigRepository
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(private val repo: ConfigRepository) : ViewModel() {
    val loggingEnabled: StateFlow<Boolean> = repo.loggingEnabledFlow

    fun setLoggingEnabled(value: Boolean) {
        repo.loggingEnabled = value
    }
}
```

- [ ] **Step 4: Build to verify**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/ui/settings/ \
        android/app/src/main/res/values/strings.xml
git commit -m "feat(ui): add SettingsScreen with 日志记录 toggle"
```

---

## Task 5: Navigation + HomeScreen wiring

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/Navigation.kt`
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/MainActivity.kt`
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/home/HomeViewModel.kt`
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/home/HomeScreen.kt`

- [ ] **Step 1: Add `Screen.Settings` to Navigation.kt**

Replace the content of `Navigation.kt`:

```kotlin
package cn.liukebin.GostX.ui

import android.net.Uri

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Logs : Screen("logs")
    object Settings : Screen("settings")
    object ConfigEdit : Screen("config/{profileId}") {
        fun createRoute(profileId: String): String = "config/${Uri.encode(profileId)}"
    }
}
```

- [ ] **Step 2: Register Settings route in MainActivity.kt**

In `MainActivity.kt`, add the following import:

```kotlin
import cn.liukebin.GostX.ui.settings.SettingsScreen
```

In the `NavHost` block, after the `Screen.Logs` composable, add:

```kotlin
composable(Screen.Settings.route) {
    SettingsScreen(
        repo = configRepository,
        onBack = { navController.popBackStack() }
    )
}
```

Update the `HomeScreen` call to pass `onNavigateToSettings`:

```kotlin
composable(Screen.Home.route) {
    HomeScreen(
        repo = configRepository,
        onRequestVpnPermission = onRequestVpnPermission,
        onNavigateToLogs = { navController.navigate(Screen.Logs.route) },
        onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
        onNavigateToConfigEdit = { profileId ->
            navController.navigate(Screen.ConfigEdit.createRoute(profileId))
        }
    )
}
```

- [ ] **Step 3: Expose `loggingEnabledFlow` in HomeViewModel**

In `HomeViewModel.kt`, add inside `class HomeViewModel`:

```kotlin
val loggingEnabled: StateFlow<Boolean> = repo.loggingEnabledFlow
    .stateIn(viewModelScope, SharingStarted.Eagerly, repo.loggingEnabled)
```

Add `StateFlow` to existing imports if not already imported (it is — already used for `vpnState`).

- [ ] **Step 4: Update HomeScreen signature and TopAppBar**

In `HomeScreen.kt`, add the parameter to `HomeScreen`:

```kotlin
fun HomeScreen(
    repo: ConfigRepository,
    onRequestVpnPermission: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},     // ← add this
    onNavigateToConfigEdit: (profileId: String) -> Unit = {},
    vm: HomeViewModel = ...
```

Add `val loggingEnabled by vm.loggingEnabled.collectAsState()` alongside the other `by` state declarations.

Replace the TopAppBar `actions` block:

```kotlin
actions = {
    IconButton(onClick = { showAddDialog = true }) {
        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.profile_add))
    }
    if (loggingEnabled) {
        IconButton(onClick = onNavigateToLogs) {
            Icon(
                Icons.AutoMirrored.Filled.Article,
                contentDescription = stringResource(R.string.nav_log)
            )
        }
    }
    IconButton(onClick = onNavigateToSettings) {
        Icon(
            Icons.Filled.Settings,
            contentDescription = stringResource(R.string.nav_settings)
        )
    }
}
```

Add to imports in `HomeScreen.kt`:
```kotlin
import androidx.compose.material.icons.filled.Settings
```

- [ ] **Step 5: Build and run all tests**

```bash
cd android && ./gradlew assembleDebug testDebugUnitTest 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/ui/Navigation.kt \
        android/app/src/main/kotlin/cn/liukebin/GostX/MainActivity.kt \
        android/app/src/main/kotlin/cn/liukebin/GostX/ui/home/HomeViewModel.kt \
        android/app/src/main/kotlin/cn/liukebin/GostX/ui/home/HomeScreen.kt
git commit -m "feat(nav): add Settings route; hide log icon when logging disabled"
```

---

## Task 6: Fix GostVpnService.onCreate — move setLogFile out

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt`

> This task fixes a detail from Task 3: `setLogFile` was called in `onCreate`. With the toggle, it should only be called when logging is enabled, and only once per VPN session start — not every `onCreate`. We move it fully into `startVpn()`.

- [ ] **Step 1: Remove setLogFile/setLoggingEnabled calls from `onCreate`**

In `GostVpnService.kt` `onCreate()`, remove these lines:

```kotlin
val loggingOn = configRepo.loggingEnabled
GostLibBridge.setLoggingEnabled(loggingOn)
if (loggingOn) {
    GostLibBridge.setLogFile(LogRepository.getLogFile().absolutePath)
        .onFailure { log("WARNING: setLogFile failed: ${it.message}") }
}
```

(If they were added there during Task 3 instead of `startVpn`, move them to `startVpn`.)

- [ ] **Step 2: Confirm placement in `startVpn()`**

In `startVpn()`, before `log("[start] loading config and starting gost...")`, ensure this block exists:

```kotlin
val loggingOn = configRepo.loggingEnabled
GostLibBridge.setLoggingEnabled(loggingOn)
if (loggingOn) {
    GostLibBridge.setLogFile(LogRepository.getLogFile().absolutePath)
        .onFailure { log("WARNING: setLogFile failed: ${it.message}") }
}
```

- [ ] **Step 3: Run tests**

```bash
cd android && ./gradlew testDebugUnitTest 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt
git commit -m "fix(service): call setLoggingEnabled+setLogFile only in startVpn, not onCreate"
```

---

## Self-Review

**Spec coverage check:**
- ✅ Go `logVPN` guarded by `loggingEnabled atomic.Bool` → Task 1
- ✅ `SetLoggingEnabled(bool)` exported from Go → Task 1
- ✅ AAR rebuild → Task 1
- ✅ `ConfigRepository.loggingEnabled` + `loggingEnabledFlow`, default `false` → Task 2
- ✅ VPN start passes flag + conditionally calls `setLogFile` → Tasks 3 + 6
- ✅ SettingsScreen with 「日志记录」Switch + restart hint → Task 4
- ✅ Log icon hidden when `loggingEnabled == false` → Task 5
- ✅ Settings icon in HomeScreen TopAppBar → Task 5
- ✅ `Screen.Settings` route → Task 5

**Type consistency:**
- `loggingEnabled` (Boolean) consistent across ConfigRepository, SettingsViewModel, HomeViewModel, GostLibBridge
- `loggingEnabledFlow: StateFlow<Boolean>` consistent across all consumers
- `GostLibBridge.setLoggingEnabled(enabled: Boolean)` matches Go `SetLoggingEnabled(v bool)`
