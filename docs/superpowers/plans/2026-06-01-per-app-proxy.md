# Per-App Proxy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add per-app VPN routing to GostX Android, letting users configure a blacklist or whitelist of apps from the Settings screen.

**Architecture:** Extend `ConfigRepository` with filter mode/list fields; extract a pure `buildAppFilterConfig` function used in `GostVpnService.startVpn()` to apply allowed/disallowed app lists on the `VpnService.Builder`; add a new `AppFilterScreen` + `AppFilterViewModel` for the app picker UI; wire it all from `SettingsScreen` via a new navigation route.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), AndroidX Navigation, `VpnService.Builder`, `PackageManager`, `SharedPreferences`, `kotlinx-coroutines`, JUnit 4 + `kotlinx-coroutines-test`

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `data/ConfigRepository.kt` | Modify | Add `AppFilterMode` enum, `appFilterMode`, `appFilterList`, their flows |
| `service/GostVpnService.kt` | Modify | Add `AppFilterConfig`, `buildAppFilterConfig`; apply filter in `startVpn()` |
| `ui/settings/AppFilterViewModel.kt` | **Create** | Loads installed apps, holds selection state, persists to repo |
| `ui/settings/AppFilterScreen.kt` | **Create** | App picker UI: search bar + checked list + "完成" |
| `ui/settings/SettingsViewModel.kt` | Modify | Expose `appFilterModeFlow`, `appFilterListFlow`; add `setAppFilterMode()` |
| `ui/settings/SettingsScreen.kt` | Modify | Add mode toggle + "管理应用" entry + app count subtitle |
| `ui/Navigation.kt` | Modify | Add `AppFilter` to `Screen` sealed class |
| `MainActivity.kt` | Modify | Wire `AppFilter` composable route in NavHost |
| `res/values/strings.xml` | Modify | Add new string resources |
| `res/values-zh/strings.xml` | Modify | Add Chinese string resources |

All test files live under `android/app/src/test/kotlin/cn/liukebin/GostX/`.
All source files live under `android/app/src/main/kotlin/cn/liukebin/GostX/`.

---

## Task 1: Extend ConfigRepository with filter data

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/data/ConfigRepository.kt`
- Modify: `android/app/src/test/kotlin/cn/liukebin/GostX/ConfigRepositoryTest.kt`

- [ ] **Step 1: Add failing tests to ConfigRepositoryTest.kt**

Append these tests inside the `ConfigRepositoryTest` class (after the existing tests):

```kotlin
@Test fun `appFilterMode defaults to BLACKLIST`() {
    assertEquals(AppFilterMode.BLACKLIST, repo.appFilterMode)
}

@Test fun `appFilterMode can be set to WHITELIST and persists serialized string`() {
    repo.appFilterMode = AppFilterMode.WHITELIST
    assertEquals(AppFilterMode.WHITELIST, repo.appFilterMode)
    assertEquals("whitelist", prefs.getString("app_filter_mode", null))
}

@Test fun `appFilterMode serializes BLACKLIST as string`() {
    repo.appFilterMode = AppFilterMode.BLACKLIST
    assertEquals("blacklist", prefs.getString("app_filter_mode", null))
}

@Test fun `appFilterModeFlow reflects current mode`() {
    assertEquals(AppFilterMode.BLACKLIST, repo.appFilterModeFlow.value)
    repo.appFilterMode = AppFilterMode.WHITELIST
    assertEquals(AppFilterMode.WHITELIST, repo.appFilterModeFlow.value)
}

@Test fun `appFilterMode survives ConfigRepository recreation`() {
    repo.appFilterMode = AppFilterMode.WHITELIST
    assertEquals(AppFilterMode.WHITELIST, ConfigRepository(prefs).appFilterMode)
}

@Test fun `appFilterList defaults to empty set`() {
    assertTrue(repo.appFilterList.isEmpty())
}

@Test fun `appFilterList can be saved and retrieved`() {
    repo.appFilterList = setOf("com.example.app1", "com.example.app2")
    assertEquals(setOf("com.example.app1", "com.example.app2"), repo.appFilterList)
}

@Test fun `appFilterListFlow reflects current list`() {
    assertTrue(repo.appFilterListFlow.value.isEmpty())
    repo.appFilterList = setOf("com.example.app")
    assertEquals(setOf("com.example.app"), repo.appFilterListFlow.value)
}

@Test fun `appFilterList survives ConfigRepository recreation`() {
    repo.appFilterList = setOf("com.example.app")
    assertEquals(setOf("com.example.app"), ConfigRepository(prefs).appFilterList)
}

@Test fun `appFilterList setter removes packages when list is reduced`() {
    repo.appFilterList = setOf("com.a", "com.b")
    repo.appFilterList = setOf("com.a")
    assertEquals(setOf("com.a"), repo.appFilterList)
}
```

Add import at the top of the test file:
```kotlin
import cn.liukebin.GostX.data.AppFilterMode
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests 'cn.liukebin.GostX.ConfigRepositoryTest' --console=plain 2>&1 | tail -20
```

Expected: compilation error — `AppFilterMode` not yet defined.

- [ ] **Step 3: Add `AppFilterMode` enum and constants to ConfigRepository.kt**

At the top of `ConfigRepository.kt`, after the existing private constants and before `DEFAULT_PROFILE_ID`, add:

```kotlin
private const val KEY_APP_FILTER_MODE = "app_filter_mode"
private const val KEY_APP_FILTER_PACKAGES = "app_filter_packages"

enum class AppFilterMode { BLACKLIST, WHITELIST }
```

- [ ] **Step 4: Add filter fields to the ConfigRepository class**

Inside the `ConfigRepository` class, after the `loggingEnabled` property block, add:

```kotlin
private val _appFilterModeFlow = MutableStateFlow(
    when (prefs.getString(KEY_APP_FILTER_MODE, null)) {
        "whitelist" -> AppFilterMode.WHITELIST
        else -> AppFilterMode.BLACKLIST
    }
)
val appFilterModeFlow: StateFlow<AppFilterMode> = _appFilterModeFlow.asStateFlow()

var appFilterMode: AppFilterMode
    get() = _appFilterModeFlow.value
    set(value) {
        prefs.edit()
            .putString(KEY_APP_FILTER_MODE, if (value == AppFilterMode.WHITELIST) "whitelist" else "blacklist")
            .apply()
        _appFilterModeFlow.value = value
    }

private val _appFilterListFlow = MutableStateFlow<Set<String>>(
    prefs.getStringSet(KEY_APP_FILTER_PACKAGES, emptySet())?.toSet() ?: emptySet()
)
val appFilterListFlow: StateFlow<Set<String>> = _appFilterListFlow.asStateFlow()

var appFilterList: Set<String>
    get() = _appFilterListFlow.value
    set(value) {
        prefs.edit().putStringSet(KEY_APP_FILTER_PACKAGES, value.toMutableSet()).apply()
        _appFilterListFlow.value = value
    }
```

- [ ] **Step 5: Run tests to confirm they pass**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests 'cn.liukebin.GostX.ConfigRepositoryTest' --console=plain 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` — all ConfigRepositoryTest tests pass.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/data/ConfigRepository.kt \
        android/app/src/test/kotlin/cn/liukebin/GostX/ConfigRepositoryTest.kt
git commit -m "feat(data): add AppFilterMode enum and filter fields to ConfigRepository"
```

---

## Task 2: Extract buildAppFilterConfig and update GostVpnService

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt`
- Create: `android/app/src/test/kotlin/cn/liukebin/GostX/AppFilterConfigTest.kt`

- [ ] **Step 1: Create failing test file AppFilterConfigTest.kt**

```kotlin
package cn.liukebin.GostX

import cn.liukebin.GostX.data.AppFilterMode
import cn.liukebin.GostX.service.AppFilterConfig
import cn.liukebin.GostX.service.buildAppFilterConfig
import org.junit.Assert.*
import org.junit.Test

class AppFilterConfigTest {
    private val SELF = "cn.liukebin.GostX"

    @Test fun `blacklist disallows user packages and self`() {
        val cfg = buildAppFilterConfig(AppFilterMode.BLACKLIST, setOf("com.a", "com.b"), SELF)
        assertEquals(setOf("com.a", "com.b", SELF), cfg.disallowed)
        assertTrue(cfg.allowed.isEmpty())
    }

    @Test fun `blacklist with empty filter list only disallows self`() {
        val cfg = buildAppFilterConfig(AppFilterMode.BLACKLIST, emptySet(), SELF)
        assertEquals(setOf(SELF), cfg.disallowed)
        assertTrue(cfg.allowed.isEmpty())
    }

    @Test fun `whitelist allows user packages`() {
        val cfg = buildAppFilterConfig(AppFilterMode.WHITELIST, setOf("com.a", "com.b"), SELF)
        assertTrue(cfg.disallowed.isEmpty())
        assertEquals(setOf("com.a", "com.b"), cfg.allowed)
    }

    @Test fun `whitelist excludes self even if present in filter list`() {
        val cfg = buildAppFilterConfig(AppFilterMode.WHITELIST, setOf("com.a", SELF), SELF)
        assertFalse(cfg.allowed.contains(SELF))
        assertTrue(cfg.disallowed.isEmpty())
    }

    @Test fun `whitelist with empty filter list produces empty allowed`() {
        val cfg = buildAppFilterConfig(AppFilterMode.WHITELIST, emptySet(), SELF)
        assertTrue(cfg.disallowed.isEmpty())
        assertTrue(cfg.allowed.isEmpty())
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests 'cn.liukebin.GostX.AppFilterConfigTest' --console=plain 2>&1 | tail -20
```

Expected: compilation error — `AppFilterConfig` and `buildAppFilterConfig` not yet defined.

- [ ] **Step 3: Add AppFilterConfig and buildAppFilterConfig to GostVpnService.kt**

At the top of `GostVpnService.kt`, after the existing `shouldRestartVpnOnNetworkAvailable` function and before `class GostVpnService`, add:

```kotlin
internal data class AppFilterConfig(
    val disallowed: Set<String>,
    val allowed: Set<String>
)

internal fun buildAppFilterConfig(
    mode: AppFilterMode,
    filterList: Set<String>,
    selfPackage: String
): AppFilterConfig = when (mode) {
    AppFilterMode.BLACKLIST -> AppFilterConfig(
        disallowed = filterList + selfPackage,
        allowed = emptySet()
    )
    AppFilterMode.WHITELIST -> AppFilterConfig(
        disallowed = emptySet(),
        allowed = filterList - selfPackage
    )
}
```

Add these imports at the top of `GostVpnService.kt`:

```kotlin
import android.content.pm.PackageManager
import cn.liukebin.GostX.data.AppFilterMode
```

- [ ] **Step 4: Run AppFilterConfigTest to confirm it passes**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests 'cn.liukebin.GostX.AppFilterConfigTest' --console=plain 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Update startVpn() in GostVpnService to apply the filter**

In `startVpn()`, find the `val builder = Builder()` block. Replace the entire chain (from `val builder = Builder()` through `.addDisallowedApplication(packageName)`) with:

```kotlin
val builder = Builder()
    .setMtu(1500)
    .addAddress("10.0.0.2", 24)
    .addRoute("0.0.0.0", 0)
    .addDnsServer(if (vpnDnsAddr.isNotEmpty()) vpnDnsAddr else "8.8.8.8")
    .setSession("GostX")
    .setBlocking(false)

val filterConfig = buildAppFilterConfig(
    mode = configRepo.appFilterMode,
    filterList = configRepo.appFilterList,
    selfPackage = packageName
)
val stalePackages = mutableSetOf<String>()
filterConfig.disallowed.forEach { pkg ->
    try {
        builder.addDisallowedApplication(pkg)
    } catch (e: PackageManager.NameNotFoundException) {
        if (pkg != packageName) stalePackages += pkg
        log("Skipping uninstalled package: $pkg")
    }
}
filterConfig.allowed.forEach { pkg ->
    try {
        builder.addAllowedApplication(pkg)
    } catch (e: PackageManager.NameNotFoundException) {
        stalePackages += pkg
        log("Removing uninstalled package from list: $pkg")
    }
}
if (stalePackages.isNotEmpty()) {
    configRepo.appFilterList = configRepo.appFilterList - stalePackages
}
```

- [ ] **Step 6: Run all tests**

```bash
cd android && ./gradlew :app:testDebugUnitTest --console=plain 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` — all tests pass.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt \
        android/app/src/test/kotlin/cn/liukebin/GostX/AppFilterConfigTest.kt
git commit -m "feat(service): extract buildAppFilterConfig and apply per-app filter in startVpn"
```

---

## Task 3: Add string resources

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values-zh/strings.xml`

- [ ] **Step 1: Add strings to values/strings.xml**

Inside `<resources>`, append after the last `<!-- Settings screen -->` block:

```xml
<!-- Per-app proxy (settings entry) -->
<string name="settings_app_filter_label">分应用代理</string>
<string name="settings_app_filter_hint">重启 VPN 后生效</string>
<string name="settings_app_filter_manage">管理应用</string>
<string name="settings_app_filter_count">%d 个应用</string>
<string name="settings_app_filter_mode_blacklist">黑名单</string>
<string name="settings_app_filter_mode_whitelist">白名单</string>

<!-- App filter screen -->
<string name="app_filter_title">分应用代理</string>
<string name="app_filter_search_hint">搜索应用名…</string>
<string name="app_filter_action_done">完成</string>
<string name="app_filter_whitelist_empty_hint">白名单模式至少选择一个应用</string>
```

- [ ] **Step 2: Add strings to values-zh/strings.xml**

Inside `<resources>`, append after the last entry:

```xml
<!-- Per-app proxy (settings entry) -->
<string name="settings_app_filter_label">分应用代理</string>
<string name="settings_app_filter_hint">重启 VPN 后生效</string>
<string name="settings_app_filter_manage">管理应用</string>
<string name="settings_app_filter_count">%d 个应用</string>
<string name="settings_app_filter_mode_blacklist">黑名单</string>
<string name="settings_app_filter_mode_whitelist">白名单</string>

<!-- App filter screen -->
<string name="app_filter_title">分应用代理</string>
<string name="app_filter_search_hint">搜索应用名…</string>
<string name="app_filter_action_done">完成</string>
<string name="app_filter_whitelist_empty_hint">白名单模式至少选择一个应用</string>
```

- [ ] **Step 3: Verify compilation**

```bash
cd android && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | grep -E "error:" | head -10
```

Expected: no output (no errors).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/res/values/strings.xml \
        android/app/src/main/res/values-zh/strings.xml
git commit -m "feat(res): add string resources for per-app proxy feature"
```

---

## Task 4: Create AppFilterViewModel

**Files:**
- Create: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/settings/AppFilterViewModel.kt`
- Create: `android/app/src/test/kotlin/cn/liukebin/GostX/AppFilterViewModelTest.kt`

- [ ] **Step 1: Create failing test file AppFilterViewModelTest.kt**

```kotlin
package cn.liukebin.GostX

import cn.liukebin.GostX.data.AppFilterMode
import cn.liukebin.GostX.data.ConfigRepository
import cn.liukebin.GostX.ui.settings.AppFilterUiState
import cn.liukebin.GostX.ui.settings.AppFilterViewModel
import cn.liukebin.GostX.ui.settings.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppFilterViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var repo: ConfigRepository

    private val fakeApps = listOf(
        InstalledApp("com.a", "App A"),
        InstalledApp("com.b", "App B"),
        InstalledApp("com.c", "Zcustom")
    )

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        prefs = FakeSharedPreferences()
        repo = ConfigRepository(prefs)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildVm(
        extra: ConfigRepository.() -> Unit = {}
    ): AppFilterViewModel {
        repo.extra()
        return AppFilterViewModel(repo) { fakeApps }
    }

    // ── AppFilterUiState pure logic ──────────────────────────────────────

    @Test fun `filtered returns all apps when query is blank`() {
        val state = AppFilterUiState(apps = fakeApps, query = "")
        assertEquals(fakeApps, state.filtered)
    }

    @Test fun `filtered is case-insensitive on label`() {
        val state = AppFilterUiState(apps = fakeApps, query = "app")
        assertEquals(listOf(InstalledApp("com.a", "App A"), InstalledApp("com.b", "App B")), state.filtered)
    }

    @Test fun `filtered returns empty when no match`() {
        val state = AppFilterUiState(apps = fakeApps, query = "xyz")
        assertTrue(state.filtered.isEmpty())
    }

    @Test fun `canSave is true in blacklist mode with empty selection`() {
        val state = AppFilterUiState(isWhitelistMode = false, selected = emptySet())
        assertTrue(state.canSave)
    }

    @Test fun `canSave is false in whitelist mode with empty selection`() {
        val state = AppFilterUiState(isWhitelistMode = true, selected = emptySet())
        assertFalse(state.canSave)
    }

    @Test fun `canSave is true in whitelist mode with non-empty selection`() {
        val state = AppFilterUiState(isWhitelistMode = true, selected = setOf("com.a"))
        assertTrue(state.canSave)
    }

    // ── ViewModel behaviour ──────────────────────────────────────────────

    @Test fun `uiState starts loading and reflects repo selection`() = runTest(dispatcher) {
        repo.appFilterList = setOf("com.a")
        val vm = buildVm()
        assertTrue(vm.uiState.value.isLoading)
        assertEquals(setOf("com.a"), vm.uiState.value.selected)
    }

    @Test fun `apps load after idle`() = runTest(dispatcher) {
        val vm = buildVm()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(fakeApps, vm.uiState.value.apps)
    }

    @Test fun `isWhitelistMode reflects repo mode at init`() = runTest(dispatcher) {
        repo.appFilterMode = AppFilterMode.WHITELIST
        val vm = buildVm()
        assertTrue(vm.uiState.value.isWhitelistMode)
    }

    @Test fun `toggleApp adds unselected package to selected`() = runTest(dispatcher) {
        val vm = buildVm()
        vm.toggleApp("com.a")
        assertTrue("com.a" in vm.uiState.value.selected)
    }

    @Test fun `toggleApp removes already-selected package`() = runTest(dispatcher) {
        val vm = buildVm { appFilterList = setOf("com.a") }
        vm.toggleApp("com.a")
        assertFalse("com.a" in vm.uiState.value.selected)
    }

    @Test fun `setQuery updates query in state`() = runTest(dispatcher) {
        val vm = buildVm()
        vm.setQuery("App")
        assertEquals("App", vm.uiState.value.query)
    }

    @Test fun `save persists selected list to repo`() = runTest(dispatcher) {
        val vm = buildVm()
        vm.toggleApp("com.a")
        vm.toggleApp("com.b")
        vm.save()
        assertEquals(setOf("com.a", "com.b"), repo.appFilterList)
    }

    @Test fun `save does nothing when canSave is false`() = runTest(dispatcher) {
        repo.appFilterMode = AppFilterMode.WHITELIST
        val vm = buildVm()
        vm.save()
        assertTrue(repo.appFilterList.isEmpty())
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests 'cn.liukebin.GostX.AppFilterViewModelTest' --console=plain 2>&1 | tail -20
```

Expected: compilation error — `AppFilterViewModel`, `InstalledApp`, `AppFilterUiState` not yet defined.

- [ ] **Step 3: Create AppFilterViewModel.kt**

Create file `android/app/src/main/kotlin/cn/liukebin/GostX/ui/settings/AppFilterViewModel.kt`:

```kotlin
package cn.liukebin.GostX.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.liukebin.GostX.data.AppFilterMode
import cn.liukebin.GostX.data.ConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String
)

data class AppFilterUiState(
    val isLoading: Boolean = true,
    val apps: List<InstalledApp> = emptyList(),
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val isWhitelistMode: Boolean = false
) {
    val filtered: List<InstalledApp>
        get() = if (query.isBlank()) apps
                else apps.filter { it.label.contains(query, ignoreCase = true) }

    val canSave: Boolean
        get() = !isWhitelistMode || selected.isNotEmpty()
}

class AppFilterViewModel(
    private val repo: ConfigRepository,
    private val appLoader: suspend () -> List<InstalledApp>
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AppFilterUiState(
            selected = repo.appFilterList,
            isWhitelistMode = repo.appFilterMode == AppFilterMode.WHITELIST
        )
    )
    val uiState: StateFlow<AppFilterUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { appLoader() }
            _uiState.update { it.copy(isLoading = false, apps = apps) }
        }
    }

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun toggleApp(packageName: String) {
        _uiState.update { state ->
            val newSelected = if (packageName in state.selected)
                state.selected - packageName
            else
                state.selected + packageName
            state.copy(selected = newSelected)
        }
    }

    fun save() {
        val state = _uiState.value
        if (!state.canSave) return
        repo.appFilterList = state.selected
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests 'cn.liukebin.GostX.AppFilterViewModelTest' --console=plain 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` — all AppFilterViewModelTest tests pass.

- [ ] **Step 5: Run all tests to confirm no regressions**

```bash
cd android && ./gradlew :app:testDebugUnitTest --console=plain 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/ui/settings/AppFilterViewModel.kt \
        android/app/src/test/kotlin/cn/liukebin/GostX/AppFilterViewModelTest.kt
git commit -m "feat(ui): add AppFilterViewModel with InstalledApp and AppFilterUiState"
```

---

## Task 5: Create AppFilterScreen

**Files:**
- Create: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/settings/AppFilterScreen.kt`

- [ ] **Step 1: Create AppFilterScreen.kt**

```kotlin
package cn.liukebin.GostX.ui.settings

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.liukebin.GostX.R
import cn.liukebin.GostX.data.ConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppFilterScreen(
    repo: ConfigRepository,
    onBack: () -> Unit = {},
    vm: AppFilterViewModel = viewModel(
        factory = remember(repo) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras
                ): T {
                    val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                    return AppFilterViewModel(repo) {
                        withContext(Dispatchers.IO) {
                            app.packageManager
                                .getInstalledApplications(PackageManager.GET_META_DATA)
                                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                                .map { info ->
                                    InstalledApp(
                                        packageName = info.packageName,
                                        label = app.packageManager.getApplicationLabel(info).toString()
                                    )
                                }
                                .sortedBy { it.label.lowercase() }
                        }
                    } as T
                }
            }
        }
    )
) {
    val uiState by vm.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_filter_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { vm.save(); onBack() },
                        enabled = uiState.canSave
                    ) {
                        Text(stringResource(R.string.app_filter_action_done))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = vm::setQuery,
                placeholder = { Text(stringResource(R.string.app_filter_search_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true
            )

            if (!uiState.canSave) {
                Text(
                    text = stringResource(R.string.app_filter_whitelist_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn {
                    items(uiState.filtered, key = { it.packageName }) { app ->
                        AppListItem(
                            app = app,
                            checked = app.packageName in uiState.selected,
                            onToggle = { vm.toggleApp(app.packageName) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppListItem(
    app: InstalledApp,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(packageName = app.packageName, modifier = Modifier.size(40.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val painter: Painter? = remember(packageName) {
        runCatching {
            val d = context.packageManager.getApplicationIcon(packageName)
            val w = d.intrinsicWidth.coerceAtLeast(1)
            val h = d.intrinsicHeight.coerceAtLeast(1)
            val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, w, h)
            d.draw(Canvas(bm))
            BitmapPainter(bm.asImageBitmap())
        }.getOrNull()
    }
    if (painter != null) {
        Image(painter = painter, contentDescription = null, modifier = modifier)
    } else {
        Icon(Icons.Default.Android, contentDescription = null, modifier = modifier)
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd android && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | grep -E "error:" | head -20
```

Expected: no output (no errors).

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/ui/settings/AppFilterScreen.kt
git commit -m "feat(ui): add AppFilterScreen with search, app list, and done button"
```

---

## Task 6: Update SettingsViewModel and SettingsScreen

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/settings/SettingsViewModel.kt`
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Update SettingsViewModel.kt**

Replace the entire file content with:

```kotlin
package cn.liukebin.GostX.ui.settings

import androidx.lifecycle.ViewModel
import cn.liukebin.GostX.data.AppFilterMode
import cn.liukebin.GostX.data.ConfigRepository
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(private val repo: ConfigRepository) : ViewModel() {
    val loggingEnabled: StateFlow<Boolean> = repo.loggingEnabledFlow
    val appFilterMode: StateFlow<AppFilterMode> = repo.appFilterModeFlow
    val appFilterList: StateFlow<Set<String>> = repo.appFilterListFlow

    fun setLoggingEnabled(value: Boolean) {
        repo.loggingEnabled = value
    }

    fun setAppFilterMode(mode: AppFilterMode) {
        repo.appFilterMode = mode
    }
}
```

- [ ] **Step 2: Update SettingsScreen.kt**

Replace the entire file content with:

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import cn.liukebin.GostX.data.AppFilterMode
import cn.liukebin.GostX.data.ConfigRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repo: ConfigRepository,
    onNavigateToAppFilter: () -> Unit = {},
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
    val appFilterMode by vm.appFilterMode.collectAsState()
    val appFilterList by vm.appFilterList.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back)
                        )
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
            // Logging toggle
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Per-app proxy section
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_app_filter_label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.settings_app_filter_count, appFilterList.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.settings_app_filter_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onNavigateToAppFilter) {
                    Text(stringResource(R.string.settings_app_filter_manage))
                }
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    onClick = { vm.setAppFilterMode(AppFilterMode.BLACKLIST) },
                    selected = appFilterMode == AppFilterMode.BLACKLIST,
                    label = { Text(stringResource(R.string.settings_app_filter_mode_blacklist)) }
                )
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    onClick = { vm.setAppFilterMode(AppFilterMode.WHITELIST) },
                    selected = appFilterMode == AppFilterMode.WHITELIST,
                    label = { Text(stringResource(R.string.settings_app_filter_mode_whitelist)) }
                )
            }
        }
    }
}
```

- [ ] **Step 3: Verify compilation**

```bash
cd android && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | grep -E "error:" | head -20
```

Expected: no output (no errors). If `onNavigateToAppFilter` parameter causes a compilation error in `MainActivity.kt`, proceed to Task 7 first.

- [ ] **Step 4: Run all tests**

```bash
cd android && ./gradlew :app:testDebugUnitTest --console=plain 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/ui/settings/SettingsViewModel.kt \
        android/app/src/main/kotlin/cn/liukebin/GostX/ui/settings/SettingsScreen.kt
git commit -m "feat(ui): add per-app filter mode toggle and manage entry to SettingsScreen"
```

---

## Task 7: Wire AppFilter navigation route

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/Navigation.kt`
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/MainActivity.kt`

- [ ] **Step 1: Add AppFilter to the Screen sealed class in Navigation.kt**

Replace the entire file content with:

```kotlin
package cn.liukebin.GostX.ui

import android.net.Uri

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Logs : Screen("logs")
    object Settings : Screen("settings")
    object AppFilter : Screen("appFilter")
    object ConfigEdit : Screen("config/{profileId}") {
        fun createRoute(profileId: String): String = "config/${Uri.encode(profileId)}"
    }
}
```

- [ ] **Step 2: Update the Settings composable call and add AppFilter route in MainActivity.kt**

In `GostXApp`, find the `composable(Screen.Settings.route)` block and replace it with:

```kotlin
composable(Screen.Settings.route) {
    SettingsScreen(
        repo = configRepository,
        onNavigateToAppFilter = { navController.navigate(Screen.AppFilter.route) },
        onBack = { navController.popBackStack() }
    )
}
composable(Screen.AppFilter.route) {
    AppFilterScreen(
        repo = configRepository,
        onBack = { navController.popBackStack() }
    )
}
```

Add the missing imports at the top of `MainActivity.kt`:

```kotlin
import cn.liukebin.GostX.ui.settings.AppFilterScreen
```

- [ ] **Step 3: Verify compilation**

```bash
cd android && ./gradlew :app:compileDebugKotlin --console=plain 2>&1 | grep -E "error:" | head -20
```

Expected: no output.

- [ ] **Step 4: Run all tests**

```bash
cd android && ./gradlew :app:testDebugUnitTest --console=plain 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/ui/Navigation.kt \
        android/app/src/main/kotlin/cn/liukebin/GostX/MainActivity.kt
git commit -m "feat(nav): add AppFilter screen route and wire from Settings"
```

---

## Done

All 7 tasks complete. The feature is fully implemented:

- `ConfigRepository` stores filter mode and package list in SharedPreferences
- `GostVpnService` applies the correct `addAllowedApplication` / `addDisallowedApplication` calls exclusively (never mixed), and auto-removes uninstalled packages from the list
- `AppFilterScreen` lets users search and select apps with a loading state
- `SettingsScreen` shows a segmented mode toggle + app count + "管理应用" navigation entry
- Navigation wired in `MainActivity`
