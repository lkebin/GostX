# Multi-Config Profile Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add multiple named YAML configuration profiles to the Android app, with a redesigned HomeScreen showing the profile list and a FAB for VPN start/stop.

**Architecture:** `ConfigRepository` gains `ConfigProfile` type, reactive `StateFlow` for live profile/active-id updates, and profile management methods. `HomeScreen` is rewritten around a profile `LazyColumn` and a FAB; `AddProfileDialog` handles creation. `ConfigScreen` is simplified to single-profile editing with delete. A new `STOPPING` `VpnStatus` state powers the FAB loading animation.

**Tech Stack:** Kotlin, Jetpack Compose + Material3, Navigation Compose 2.7, SharedPreferences, StateFlow/SharedFlow, kotlinx-coroutines, JUnit 4, mockito-kotlin

**Run tests:** `cd android && ./gradlew :app:testDebugUnitTest`

---

### Task 1: ConfigRepository – ConfigProfile, reactive flows, addProfile, getNextDefaultName, deleteProfile

**Files:**
- Create: `android/app/src/test/kotlin/cn/liukebin/GostX/FakeSharedPreferences.kt`
- Create: `android/app/src/test/kotlin/cn/liukebin/GostX/ConfigRepositoryTest.kt`
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/data/ConfigRepository.kt`

- [ ] **Step 1: Create FakeSharedPreferences test helper**

```kotlin
// android/app/src/test/kotlin/cn/liukebin/GostX/FakeSharedPreferences.kt
package cn.liukebin.GostX

import android.content.SharedPreferences

class FakeSharedPreferences : SharedPreferences {
    val store = mutableMapOf<String, Any?>()

    override fun contains(key: String) = store.containsKey(key)
    override fun getAll(): MutableMap<String, *> = store
    override fun getString(key: String?, defValue: String?) = store[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String?, defValue: Int) = store[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = store[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = store[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = store[key] as? Boolean ?: defValue
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = Editor()

    inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        override fun putString(key: String?, value: String?) = this.also { if (key != null) pending[key] = value }
        override fun putBoolean(key: String?, value: Boolean) = this.also { if (key != null) pending[key] = value }
        override fun putInt(key: String?, value: Int) = this.also { if (key != null) pending[key] = value }
        override fun putLong(key: String?, value: Long) = this.also { if (key != null) pending[key] = value }
        override fun putFloat(key: String?, value: Float) = this.also { if (key != null) pending[key] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = this.also { if (key != null) pending[key] = values }
        override fun remove(key: String?) = this.also { if (key != null) removals.add(key) }
        override fun clear() = this.also { store.clear() }
        override fun commit(): Boolean { flush(); return true }
        override fun apply() { flush() }
        private fun flush() { removals.forEach { store.remove(it) }; store.putAll(pending) }
    }
}
```

- [ ] **Step 2: Write failing tests for ConfigRepository**

```kotlin
// android/app/src/test/kotlin/cn/liukebin/GostX/ConfigRepositoryTest.kt
package cn.liukebin.GostX

import cn.liukebin.GostX.data.ConfigRepository
import cn.liukebin.GostX.data.ConfigProfile
import cn.liukebin.GostX.data.DEFAULT_PROFILE_ID
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConfigRepositoryTest {
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var repo: ConfigRepository

    @Before fun setup() {
        prefs = FakeSharedPreferences()
        repo = ConfigRepository(prefs)
    }

    @Test fun `getProfiles returns default profile as ConfigProfile on first run`() {
        val profiles = repo.getProfiles()
        assertEquals(1, profiles.size)
        assertEquals(ConfigProfile(DEFAULT_PROFILE_ID, DEFAULT_PROFILE_ID), profiles[0])
    }

    @Test fun `getNextDefaultName returns Config 1 when no numbered profiles exist`() {
        assertEquals("Config 1", repo.getNextDefaultName())
    }

    @Test fun `getNextDefaultName increments past existing numbered profiles`() {
        repo.addProfile("Config 1")
        assertEquals("Config 2", repo.getNextDefaultName())
    }

    @Test fun `getNextDefaultName skips non-sequential gaps`() {
        repo.addProfile("Config 1")
        repo.addProfile("Config 2")
        repo.addProfile("Config 3")
        assertEquals("Config 4", repo.getNextDefaultName())
    }

    @Test fun `addProfile returns true for new unique name`() {
        assertTrue(repo.addProfile("MyProfile"))
    }

    @Test fun `addProfile returns false for duplicate name`() {
        repo.addProfile("MyProfile")
        assertFalse(repo.addProfile("MyProfile"))
    }

    @Test fun `addProfile returns false for duplicate of existing default`() {
        assertFalse(repo.addProfile(DEFAULT_PROFILE_ID))
    }

    @Test fun `addProfile creates profile with empty yaml`() {
        repo.addProfile("NewConfig")
        assertEquals("", repo.getConfig("NewConfig"))
    }

    @Test fun `addProfile makes profile appear in getProfiles`() {
        repo.addProfile("NewConfig")
        assertTrue(repo.getProfiles().any { it.id == "NewConfig" })
    }

    @Test fun `deleteProfile of non-active does not change active`() {
        repo.addProfile("Other")
        val activeBefore = repo.getActiveProfileId()
        repo.deleteProfile("Other")
        assertEquals(activeBefore, repo.getActiveProfileId())
    }

    @Test fun `deleteProfile of active profile switches active to first remaining`() {
        repo.addProfile("Second")
        repo.setActiveProfile("Second")
        repo.deleteProfile("Second")
        assertEquals(DEFAULT_PROFILE_ID, repo.getActiveProfileId())
    }

    @Test fun `deleteProfile removes profile from list`() {
        repo.addProfile("ToDelete")
        repo.deleteProfile("ToDelete")
        assertFalse(repo.getProfiles().any { it.id == "ToDelete" })
    }

    @Test fun `profilesFlow initial value reflects stored profiles`() {
        assertEquals(repo.getProfiles(), repo.profilesFlow.value)
    }

    @Test fun `profilesFlow updates after addProfile`() {
        repo.addProfile("New")
        assertTrue(repo.profilesFlow.value.any { it.id == "New" })
    }

    @Test fun `profilesFlow updates after deleteProfile`() {
        repo.addProfile("ToRemove")
        repo.deleteProfile("ToRemove")
        assertFalse(repo.profilesFlow.value.any { it.id == "ToRemove" })
    }

    @Test fun `activeProfileIdFlow updates after setActiveProfile`() {
        repo.addProfile("Second")
        repo.setActiveProfile("Second")
        assertEquals("Second", repo.activeProfileIdFlow.value)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "cn.liukebin.GostX.ConfigRepositoryTest" 2>&1 | tail -30
```

Expected: compilation errors or test failures (ConfigProfile does not exist yet).

- [ ] **Step 4: Update ConfigRepository.kt**

Replace the entire file:

```kotlin
// android/app/src/main/kotlin/cn/liukebin/GostX/data/ConfigRepository.kt
package cn.liukebin.GostX.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val KEY_PROFILES = "config_profile_list"
private const val KEY_ACTIVE = "config_active_profile"
const val DEFAULT_PROFILE_ID = "default"

data class ConfigProfile(val id: String, val name: String)

val DEFAULT_YAML = """
# GostX VPN 配置
# 使用 tungo 模式：gVisor 直接处理 TUN 数据包，无需额外监听端口
#
# ⚠️  metadata 位置很重要：
#   - connector 的参数（如 SS 的 method/password）放在 connector.metadata 下
#   - dialer 的参数（如 WSS 的 path、host）放在 dialer.metadata 下
#   - 不要把 dialer 参数放在节点级别的 metadata 下，否则会被忽略
#
# 示例 1: Shadowsocks over TCP（最简单）
services:
  - name: vpn
    addr: :0
    handler:
      type: tungo
      chain: upstream
    listener:
      type: tungo

chains:
  - name: upstream
    hops:
      - name: hop0
        nodes:
          - name: server
            addr: 您的代理服务器:端口
            connector:
              type: ss
              metadata:
                method: chacha20-ietf-poly1305
                password: 您的密码
            dialer:
              type: tcp

# 示例 2: HTTP CONNECT over WebSocket (WSS)
# chains:
#   - name: upstream
#     hops:
#       - name: hop0
#         nodes:
#           - name: server
#             addr: your.server.com:443
#             connector:
#               type: http
#             dialer:
#               type: wss
#               metadata:
#                 path: /your-secret-path   # ← 必须在 dialer.metadata 下，不是节点级别
""".trimIndent()

class ConfigRepository(private val prefs: SharedPreferences) {

    private val _profilesFlow = MutableStateFlow<List<ConfigProfile>>(emptyList())
    val profilesFlow: StateFlow<List<ConfigProfile>> = _profilesFlow.asStateFlow()

    private val _activeProfileIdFlow = MutableStateFlow(DEFAULT_PROFILE_ID)
    val activeProfileIdFlow: StateFlow<String> = _activeProfileIdFlow.asStateFlow()

    init {
        _profilesFlow.value = loadProfilesFromPrefs()
        _activeProfileIdFlow.value = prefs.getString(KEY_ACTIVE, DEFAULT_PROFILE_ID) ?: DEFAULT_PROFILE_ID
    }

    fun getProfiles(): List<ConfigProfile> = _profilesFlow.value

    fun getActiveProfileId(): String = _activeProfileIdFlow.value

    fun setActiveProfile(id: String) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
        _activeProfileIdFlow.value = id
    }

    fun getConfig(profileId: String): String =
        prefs.getString("config_profile_$profileId", null)
            ?: if (profileId == DEFAULT_PROFILE_ID) DEFAULT_YAML else ""

    fun getActiveConfig(): String = getConfig(getActiveProfileId())

    fun saveConfig(profileId: String, yaml: String) {
        val editor = prefs.edit().putString("config_profile_$profileId", yaml)
        val profiles = _profilesFlow.value
        if (profiles.none { it.id == profileId }) {
            val newList = profiles + ConfigProfile(profileId, profileId)
            editor.putString(KEY_PROFILES, newList.joinToString(",") { it.id })
            editor.apply()
            _profilesFlow.value = newList
        } else {
            editor.apply()
        }
    }

    /** Returns the first unused name in the series "Config 1", "Config 2", … */
    fun getNextDefaultName(): String {
        val existingNames = _profilesFlow.value.map { it.name }.toSet()
        var n = 1
        while ("Config $n" in existingNames) n++
        return "Config $n"
    }

    /**
     * Creates a new profile with empty YAML content.
     * Returns false if [name] is already taken, true on success.
     */
    fun addProfile(name: String): Boolean {
        val current = _profilesFlow.value
        if (current.any { it.id == name }) return false
        val newList = current + ConfigProfile(name, name)
        prefs.edit()
            .putString(KEY_PROFILES, newList.joinToString(",") { it.id })
            .putString("config_profile_$name", "")
            .apply()
        _profilesFlow.value = newList
        return true
    }

    fun deleteProfile(profileId: String) {
        val current = _profilesFlow.value
        if (current.size <= 1) return
        val newList = current.filter { it.id != profileId }
        val editor = prefs.edit()
            .putString(KEY_PROFILES, newList.joinToString(",") { it.id })
            .remove("config_profile_$profileId")
        if (_activeProfileIdFlow.value == profileId) {
            val newActive = newList.first().id
            editor.putString(KEY_ACTIVE, newActive)
            _activeProfileIdFlow.value = newActive
        }
        editor.apply()
        _profilesFlow.value = newList
    }

    private fun loadProfilesFromPrefs(): List<ConfigProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return listOf(ConfigProfile(DEFAULT_PROFILE_ID, DEFAULT_PROFILE_ID))
        return raw.split(",").filter { it.isNotBlank() }.map { ConfigProfile(it, it) }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "cn.liukebin.GostX.ConfigRepositoryTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all 16 tests pass.

- [ ] **Step 6: Commit**

```bash
cd android && git add app/src/test/kotlin/cn/liukebin/GostX/FakeSharedPreferences.kt \
  app/src/test/kotlin/cn/liukebin/GostX/ConfigRepositoryTest.kt \
  app/src/main/kotlin/cn/liukebin/GostX/data/ConfigRepository.kt
git commit -m "feat(data): add ConfigProfile, reactive flows, addProfile, getNextDefaultName

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: VpnStatus.STOPPING + GostVpnService + HomeViewModel logic

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/data/VpnStateRepository.kt`
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt`
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/home/HomeViewModel.kt`
- Modify: `android/app/src/test/kotlin/cn/liukebin/GostX/HomeViewModelLogicTest.kt`

- [ ] **Step 1: Write failing tests**

Add to `HomeViewModelLogicTest.kt` (append below existing tests):

```kotlin
    // resolveVpnToggleAction with STOPPING
    @Test fun `stopping vpn resolves to stop action`() {
        assertEquals(
            VpnToggleAction.STOP,
            resolveVpnToggleAction(VpnStatus.STOPPING, hasVpnPermission = true)
        )
    }

    @Test fun `error vpn with permission starts service`() {
        assertEquals(
            VpnToggleAction.START,
            resolveVpnToggleAction(VpnStatus.ERROR, hasVpnPermission = true)
        )
    }

    // canSetActiveProfile
    @Test fun `can set active profile when stopped`() {
        assertTrue(canSetActiveProfile(VpnStatus.STOPPED))
    }

    @Test fun `can set active profile when error`() {
        assertTrue(canSetActiveProfile(VpnStatus.ERROR))
    }

    @Test fun `cannot set active profile when connecting`() {
        assertFalse(canSetActiveProfile(VpnStatus.CONNECTING))
    }

    @Test fun `cannot set active profile when connected`() {
        assertFalse(canSetActiveProfile(VpnStatus.CONNECTED))
    }

    @Test fun `cannot set active profile when stopping`() {
        assertFalse(canSetActiveProfile(VpnStatus.STOPPING))
    }
```

Also add the import at the top of the file:
```kotlin
import cn.liukebin.GostX.ui.home.canSetActiveProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
```

- [ ] **Step 2: Run to verify failure**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "cn.liukebin.GostX.HomeViewModelLogicTest" 2>&1 | tail -20
```

Expected: compilation errors — `VpnStatus.STOPPING` and `canSetActiveProfile` do not exist yet.

- [ ] **Step 3: Update VpnStateRepository.kt**

```kotlin
// android/app/src/main/kotlin/cn/liukebin/GostX/data/VpnStateRepository.kt
package cn.liukebin.GostX.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnStatus { STOPPED, CONNECTING, CONNECTED, STOPPING, ERROR }

data class VpnState(
    val status: VpnStatus = VpnStatus.STOPPED,
    val listenAddr: String = "",
    val error: String? = null
)

open class VpnStateRepository {
    private val _state = MutableStateFlow(VpnState())
    val state: StateFlow<VpnState> = _state.asStateFlow()

    fun setState(s: VpnState) { _state.value = s }
    fun setConnecting() = setState(VpnState(VpnStatus.CONNECTING))
    fun setConnected(addr: String) = setState(VpnState(VpnStatus.CONNECTED, addr))
    fun setStopping() = setState(VpnState(VpnStatus.STOPPING))
    fun setStopped() = setState(VpnState(VpnStatus.STOPPED))
    fun setError(msg: String) = setState(VpnState(VpnStatus.ERROR, error = msg))
}

object GlobalVpnState : VpnStateRepository()
```

- [ ] **Step 4: Update resolveVpnToggleAction and add canSetActiveProfile in HomeViewModel.kt**

In `HomeViewModel.kt`, update the two top-level functions:

```kotlin
enum class VpnToggleAction { START, REQUEST_PERMISSION, STOP }

internal fun resolveVpnToggleAction(status: VpnStatus, hasVpnPermission: Boolean): VpnToggleAction = when {
    status == VpnStatus.CONNECTED || status == VpnStatus.CONNECTING || status == VpnStatus.STOPPING -> VpnToggleAction.STOP
    hasVpnPermission -> VpnToggleAction.START
    else -> VpnToggleAction.REQUEST_PERMISSION
}

internal fun canSetActiveProfile(status: VpnStatus): Boolean =
    status == VpnStatus.STOPPED || status == VpnStatus.ERROR
```

(The `HomeViewModel` class body itself will be fully rewritten in Task 6; keep the existing class body for now — only update these two top-level functions.)

- [ ] **Step 5: Update GostVpnService.kt – call setStopping() at stopVpn() entry**

In `GostVpnService.kt`, find `private fun stopVpn(updatePersistentState: Boolean = true) {` and add `GlobalVpnState.setStopping()` as the very first line:

```kotlin
private fun stopVpn(updatePersistentState: Boolean = true) {
    GlobalVpnState.setStopping()
    unregisterNetworkCallback()
    vpnLogJob?.cancel()
    // ... rest unchanged
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "cn.liukebin.GostX.HomeViewModelLogicTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all 10 tests pass.

- [ ] **Step 7: Commit**

```bash
cd android && git add app/src/main/kotlin/cn/liukebin/GostX/data/VpnStateRepository.kt \
  app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt \
  app/src/main/kotlin/cn/liukebin/GostX/ui/home/HomeViewModel.kt \
  app/src/test/kotlin/cn/liukebin/GostX/HomeViewModelLogicTest.kt
git commit -m "feat(vpn): add STOPPING state, setStopping, canSetActiveProfile

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: Navigation – Screen.ConfigEdit route with profileId

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/Navigation.kt`

- [ ] **Step 1: Update Navigation.kt**

```kotlin
// android/app/src/main/kotlin/cn/liukebin/GostX/ui/Navigation.kt
package cn.liukebin.GostX.ui

import android.net.Uri

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Logs : Screen("logs")
    object ConfigEdit : Screen("config/{profileId}") {
        fun createRoute(profileId: String): String = "config/${Uri.encode(profileId)}"
    }
}
```

- [ ] **Step 2: Verify compilation (no unit tests for this task)**

```bash
cd android && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:|warning:" | head -20
```

Expected: errors about the old `Screen.Config` usages in `MainActivity.kt`. These will be fixed in Task 9. For now note which lines fail; do NOT fix them yet.

- [ ] **Step 3: Commit**

```bash
cd android && git add app/src/main/kotlin/cn/liukebin/GostX/ui/Navigation.kt
git commit -m "feat(nav): replace Screen.Config with Screen.ConfigEdit(profileId)

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: ConfigViewModel – single-profile editing, canDelete, deleteProfile with nav event

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/config/ConfigViewModel.kt`

- [ ] **Step 1: Replace ConfigViewModel.kt entirely**

```kotlin
// android/app/src/main/kotlin/cn/liukebin/GostX/ui/config/ConfigViewModel.kt
package cn.liukebin.GostX.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.liukebin.GostX.data.ConfigRepository
import cn.liukebin.GostX.data.GlobalVpnState
import cn.liukebin.GostX.data.VpnStatus
import cn.liukebin.GostX.service.GostLibBridge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ConfigUiState(
    val profileName: String = "",
    val yaml: String = "",
    val validationError: String? = null,
    val isSaved: Boolean = false,
    val canDelete: Boolean = false
)

class ConfigViewModel(
    private val repo: ConfigRepository,
    private val profileId: String
) : ViewModel() {

    private val _ui = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _ui.asStateFlow()

    private val _navBack = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navBack: SharedFlow<Unit> = _navBack.asSharedFlow()

    init {
        load()
        viewModelScope.launch {
            GlobalVpnState.state.collect { vpnState ->
                _ui.value = _ui.value.copy(canDelete = computeCanDelete(vpnState.status))
            }
        }
    }

    private fun computeCanDelete(status: VpnStatus): Boolean =
        repo.getProfiles().size > 1 && (status == VpnStatus.STOPPED || status == VpnStatus.ERROR)

    private fun load() {
        val profiles = repo.getProfiles()
        val name = profiles.find { it.id == profileId }?.name ?: profileId
        val status = GlobalVpnState.state.value.status
        _ui.value = ConfigUiState(
            profileName = name,
            yaml = repo.getConfig(profileId),
            canDelete = computeCanDelete(status)
        )
    }

    fun onYamlChange(yaml: String) {
        _ui.value = _ui.value.copy(yaml = yaml, validationError = null, isSaved = false)
    }

    fun save() {
        val error = GostLibBridge.validateConfig(_ui.value.yaml)
        if (error.isNotEmpty()) {
            _ui.value = _ui.value.copy(validationError = error)
            return
        }
        repo.saveConfig(profileId, _ui.value.yaml)
        _ui.value = _ui.value.copy(isSaved = true, validationError = null)
    }

    fun clearValidationError() {
        _ui.value = _ui.value.copy(validationError = null)
    }

    fun deleteProfile() {
        if (!_ui.value.canDelete) return
        repo.deleteProfile(profileId)
        _navBack.tryEmit(Unit)
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd android && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:" | grep -v "Navigation\|MainActivity\|ConfigScreen" | head -20
```

Expected: no errors in `ConfigViewModel.kt`. Errors in `ConfigScreen.kt` and `MainActivity.kt` are expected and fixed in later tasks.

- [ ] **Step 3: Commit**

```bash
cd android && git add app/src/main/kotlin/cn/liukebin/GostX/ui/config/ConfigViewModel.kt
git commit -m "feat(config): ConfigViewModel accepts profileId, adds deleteProfile with nav event

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: ConfigScreen – remove profile chips, add delete action

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/config/ConfigScreen.kt`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values-zh/strings.xml`

- [ ] **Step 1: Add new strings**

In `android/app/src/main/res/values/strings.xml`, add inside `<resources>`:
```xml
    <!-- Multi-config additions -->
    <string name="action_delete">Delete</string>
    <string name="profile_delete_confirm_title">Delete Profile</string>
    <string name="profile_delete_confirm_message">Delete \"%s\"?</string>
    <string name="action_cancel">Cancel</string>
    <string name="profile_add">Add Profile</string>
    <string name="profile_new_title">New Profile</string>
    <string name="profile_name_label">Name</string>
    <string name="profile_name_duplicate">Name already exists</string>
    <string name="action_create">Create</string>
    <string name="vpn_start_label">Start VPN</string>
    <string name="vpn_stop_label">Stop VPN</string>
    <string name="vpn_loading_label">Loading</string>
```

In `android/app/src/main/res/values-zh/strings.xml`, add inside `<resources>`:
```xml
    <!-- Multi-config additions -->
    <string name="action_delete">删除</string>
    <string name="profile_delete_confirm_title">删除配置</string>
    <string name="profile_delete_confirm_message">确定要删除 \"%s\" 吗？</string>
    <string name="action_cancel">取消</string>
    <string name="profile_add">添加配置</string>
    <string name="profile_new_title">新建配置</string>
    <string name="profile_name_label">名称</string>
    <string name="profile_name_duplicate">名称已存在</string>
    <string name="action_create">创建</string>
    <string name="vpn_start_label">启动 VPN</string>
    <string name="vpn_stop_label">停止 VPN</string>
    <string name="vpn_loading_label">加载中</string>
```

- [ ] **Step 2: Replace ConfigScreen.kt**

```kotlin
// android/app/src/main/kotlin/cn/liukebin/GostX/ui/config/ConfigScreen.kt
package cn.liukebin.GostX.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.liukebin.GostX.R
import cn.liukebin.GostX.data.ConfigRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    repo: ConfigRepository,
    profileId: String,
    onBack: () -> Unit,
    vm: ConfigViewModel = viewModel(
        key = profileId,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ConfigViewModel(repo, profileId) as T
        }
    )
) {
    val state by vm.uiState.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.navBack.collect { onBack() }
    }

    if (state.validationError != null) {
        AlertDialog(
            onDismissRequest = { vm.clearValidationError() },
            title = { Text(stringResource(R.string.config_error_title)) },
            text = { Text(state.validationError!!) },
            confirmButton = {
                TextButton(onClick = { vm.clearValidationError() }) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.profile_delete_confirm_title)) },
            text = { Text(stringResource(R.string.profile_delete_confirm_message, state.profileName)) },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; vm.deleteProfile() }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.profileName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.save() }) {
                        Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.action_save))
                    }
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        enabled = state.canDelete
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.action_delete)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = state.yaml,
                onValueChange = { vm.onYamlChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            )

            if (state.isSaved) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.config_saved),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
```

- [ ] **Step 3: Verify compilation of ConfigScreen**

```bash
cd android && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:" | grep "ConfigScreen" | head -20
```

Expected: no errors in `ConfigScreen.kt`. Errors in `MainActivity.kt` are expected (fixed in Task 9).

- [ ] **Step 4: Commit**

```bash
cd android && git add app/src/main/kotlin/cn/liukebin/GostX/ui/config/ConfigScreen.kt \
  app/src/main/res/values/strings.xml \
  app/src/main/res/values-zh/strings.xml
git commit -m "feat(config): remove profile chips, add delete action with confirmation

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: HomeViewModel – inject ConfigRepository, expose homeState, setActiveProfile, addProfile

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/home/HomeViewModel.kt`

- [ ] **Step 1: Replace HomeViewModel.kt**

```kotlin
// android/app/src/main/kotlin/cn/liukebin/GostX/ui/home/HomeViewModel.kt
package cn.liukebin.GostX.ui.home

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.liukebin.GostX.data.ConfigProfile
import cn.liukebin.GostX.data.ConfigRepository
import cn.liukebin.GostX.data.GlobalVpnState
import cn.liukebin.GostX.data.VpnStatus
import cn.liukebin.GostX.service.GostVpnService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class VpnToggleAction { START, REQUEST_PERMISSION, STOP }

internal fun resolveVpnToggleAction(status: VpnStatus, hasVpnPermission: Boolean): VpnToggleAction = when {
    status == VpnStatus.CONNECTED || status == VpnStatus.CONNECTING || status == VpnStatus.STOPPING -> VpnToggleAction.STOP
    hasVpnPermission -> VpnToggleAction.START
    else -> VpnToggleAction.REQUEST_PERMISSION
}

internal fun canSetActiveProfile(status: VpnStatus): Boolean =
    status == VpnStatus.STOPPED || status == VpnStatus.ERROR

data class HomeUiState(
    val profiles: List<ConfigProfile> = emptyList(),
    val activeProfileId: String = ""
)

class HomeViewModel(
    app: Application,
    private val repo: ConfigRepository
) : AndroidViewModel(app) {

    val vpnState = GlobalVpnState.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, GlobalVpnState.state.value)

    val homeState: StateFlow<HomeUiState> = combine(
        repo.profilesFlow,
        repo.activeProfileIdFlow
    ) { profiles, activeId -> HomeUiState(profiles = profiles, activeProfileId = activeId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    fun setActiveProfile(profileId: String) {
        if (!canSetActiveProfile(vpnState.value.status)) return
        repo.setActiveProfile(profileId)
    }

    /** Returns false if the name is already taken. */
    fun addProfile(name: String): Boolean = repo.addProfile(name)

    fun toggleVpn(onVpnPermissionRequired: () -> Unit = {}) {
        val ctx = getApplication<Application>()
        when (resolveVpnToggleAction(vpnState.value.status, VpnService.prepare(ctx) == null)) {
            VpnToggleAction.STOP -> startService(ctx, GostVpnService.ACTION_STOP)
            VpnToggleAction.START -> startService(ctx, GostVpnService.ACTION_START)
            VpnToggleAction.REQUEST_PERMISSION -> onVpnPermissionRequired()
        }
    }

    private fun startService(ctx: Application, action: String) {
        val intent = Intent(ctx, GostVpnService::class.java).apply { this.action = action }
        if (action == GostVpnService.ACTION_START) ctx.startForegroundService(intent)
        else ctx.startService(intent)
    }
}
```

- [ ] **Step 2: Verify tests still pass**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "cn.liukebin.GostX.HomeViewModelLogicTest" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
cd android && git add app/src/main/kotlin/cn/liukebin/GostX/ui/home/HomeViewModel.kt
git commit -m "feat(home): HomeViewModel injects ConfigRepository, exposes homeState via combine flow

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: AddProfileDialog – new composable

**Files:**
- Create: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/home/AddProfileDialog.kt`

- [ ] **Step 1: Create AddProfileDialog.kt**

```kotlin
// android/app/src/main/kotlin/cn/liukebin/GostX/ui/home/AddProfileDialog.kt
package cn.liukebin.GostX.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import cn.liukebin.GostX.R

/**
 * Dialog for creating a new configuration profile.
 *
 * @param existingNames set of already-used profile names (for duplicate detection)
 * @param initialName pre-filled name, typically from [cn.liukebin.GostX.data.ConfigRepository.getNextDefaultName]
 * @param onConfirm called with the trimmed name when the user taps "Create"
 * @param onDismiss called when the user taps "Cancel" or dismisses the dialog
 */
@Composable
fun AddProfileDialog(
    existingNames: Set<String>,
    initialName: String,
    onConfirm: (name: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    val trimmed = name.trim()
    val isDuplicate = trimmed in existingNames
    val isInvalid = trimmed.isEmpty() || isDuplicate

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_new_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.profile_name_label)) },
                    singleLine = true,
                    isError = isDuplicate,
                    supportingText = if (isDuplicate) {
                        { Text(stringResource(R.string.profile_name_duplicate), color = MaterialTheme.colorScheme.error) }
                    } else null
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = !isInvalid
            ) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd android && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:" | grep "AddProfileDialog" | head -10
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
cd android && git add app/src/main/kotlin/cn/liukebin/GostX/ui/home/AddProfileDialog.kt
git commit -m "feat(home): add AddProfileDialog with duplicate name validation

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 8: HomeScreen – full rewrite

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/home/HomeScreen.kt`

- [ ] **Step 1: Replace HomeScreen.kt**

```kotlin
// android/app/src/main/kotlin/cn/liukebin/GostX/ui/home/HomeScreen.kt
package cn.liukebin.GostX.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.liukebin.GostX.R
import cn.liukebin.GostX.data.ConfigProfile
import cn.liukebin.GostX.data.ConfigRepository
import cn.liukebin.GostX.data.VpnStatus
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repo: ConfigRepository,
    onRequestVpnPermission: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    onNavigateToConfigEdit: (profileId: String) -> Unit = {},
    vm: HomeViewModel = viewModel(
        factory = remember(repo) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras
                ): T {
                    val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                    return HomeViewModel(app, repo) as T
                }
            }
        }
    )
) {
    val vpnState by vm.vpnState.collectAsState()
    val homeState by vm.homeState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(vpnState) {
        when {
            vpnState.status == VpnStatus.CONNECTED && vpnState.listenAddr.isNotEmpty() ->
                snackbarHostState.showSnackbar("监听: ${vpnState.listenAddr}")
            vpnState.status == VpnStatus.ERROR && vpnState.error != null ->
                snackbarHostState.showSnackbar(vpnState.error!!)
        }
    }

    if (showAddDialog) {
        AddProfileDialog(
            existingNames = homeState.profiles.map { it.name }.toSet(),
            initialName = repo.getNextDefaultName(),
            onConfirm = { name ->
                if (vm.addProfile(name)) {
                    showAddDialog = false
                    onNavigateToConfigEdit(name)
                }
            },
            onDismiss = { showAddDialog = false }
        )
    }

    val isTransitioning = vpnState.status == VpnStatus.CONNECTING || vpnState.status == VpnStatus.STOPPING

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GostX") },
                actions = {
                    IconButton(onClick = onNavigateToLogs) {
                        Icon(
                            Icons.AutoMirrored.Filled.Article,
                            contentDescription = stringResource(R.string.nav_log)
                        )
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.profile_add)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (!isTransitioning) vm.toggleVpn(onRequestVpnPermission) },
                modifier = Modifier.alpha(if (isTransitioning) 0.5f else 1f),
                containerColor = when (vpnState.status) {
                    VpnStatus.CONNECTED -> Color(0xFF4CAF50)
                    VpnStatus.ERROR -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primaryContainer
                }
            ) {
                if (isTransitioning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_tile_vpn),
                        contentDescription = if (vpnState.status == VpnStatus.CONNECTED)
                            stringResource(R.string.vpn_stop_label)
                        else
                            stringResource(R.string.vpn_start_label)
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            items(homeState.profiles, key = { it.id }) { profile ->
                ProfileListItem(
                    profile = profile,
                    isActive = profile.id == homeState.activeProfileId,
                    radioEnabled = canSetActiveProfile(vpnState.status),
                    onActivate = { vm.setActiveProfile(profile.id) },
                    onEdit = { onNavigateToConfigEdit(profile.id) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ProfileListItem(
    profile: ConfigProfile,
    isActive: Boolean,
    radioEnabled: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isActive,
            onClick = onActivate,
            enabled = radioEnabled,
            modifier = Modifier.padding(start = 8.dp)
        )
        Text(
            text = profile.name,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            style = MaterialTheme.typography.bodyLarge
        )
        IconButton(onClick = onEdit) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.nav_config)
            )
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd android && ./gradlew :app:compileDebugKotlin 2>&1 | grep -E "error:" | grep "HomeScreen" | head -20
```

Expected: no errors in `HomeScreen.kt`. Remaining errors in `MainActivity.kt` are expected.

- [ ] **Step 3: Commit**

```bash
cd android && git add app/src/main/kotlin/cn/liukebin/GostX/ui/home/HomeScreen.kt
git commit -m "feat(home): rewrite HomeScreen with profile list and FAB start/stop

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 9: MainActivity – wire ConfigRepository to HomeScreen, bind ConfigEdit route

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/MainActivity.kt`

- [ ] **Step 1: Replace MainActivity.kt**

```kotlin
// android/app/src/main/kotlin/cn/liukebin/GostX/MainActivity.kt
package cn.liukebin.GostX

import android.app.Activity
import android.content.Context
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cn.liukebin.GostX.data.ConfigRepository
import cn.liukebin.GostX.data.DEFAULT_PROFILE_ID
import cn.liukebin.GostX.data.GlobalVpnState
import cn.liukebin.GostX.service.GostVpnService
import cn.liukebin.GostX.ui.Screen
import cn.liukebin.GostX.ui.config.ConfigScreen
import cn.liukebin.GostX.ui.home.HomeScreen
import cn.liukebin.GostX.ui.log.LogScreen

class MainActivity : ComponentActivity() {
    private lateinit var configRepository: ConfigRepository

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            GostVpnService.start(this)
        } else {
            GlobalVpnState.setError(getString(R.string.vpn_permission_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE)
        configRepository = ConfigRepository(prefs)
        setContent {
            GostXApp(
                configRepository = configRepository,
                onRequestVpnPermission = ::requestVpnPermission
            )
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent == null) GostVpnService.start(this)
        else vpnPermissionLauncher.launch(intent)
    }
}

@Composable
fun GostXApp(
    configRepository: ConfigRepository,
    onRequestVpnPermission: () -> Unit = {}
) {
    val navController = rememberNavController()
    MaterialTheme {
        Scaffold { padding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier.padding(padding),
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        repo = configRepository,
                        onRequestVpnPermission = onRequestVpnPermission,
                        onNavigateToLogs = { navController.navigate(Screen.Logs.route) },
                        onNavigateToConfigEdit = { profileId ->
                            navController.navigate(Screen.ConfigEdit.createRoute(profileId))
                        }
                    )
                }
                composable(Screen.Logs.route) {
                    LogScreen(onBack = { navController.popBackStack() })
                }
                composable(
                    route = Screen.ConfigEdit.route,
                    arguments = listOf(navArgument("profileId") { type = NavType.StringType })
                ) { backStackEntry ->
                    val profileId = backStackEntry.arguments?.getString("profileId") ?: DEFAULT_PROFILE_ID
                    ConfigScreen(
                        repo = configRepository,
                        profileId = profileId,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: Run full build**

```bash
cd android && ./gradlew :app:assembleDebug 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run all unit tests**

```bash
cd android && ./gradlew :app:testDebugUnitTest 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 4: Commit**

```bash
cd android && git add app/src/main/kotlin/cn/liukebin/GostX/MainActivity.kt
git commit -m "feat(main): wire ConfigRepository to HomeScreen, bind ConfigEdit route

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 10: Final integration check

- [ ] **Step 1: Run all unit tests one last time**

```bash
cd android && ./gradlew :app:testDebugUnitTest 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Manual smoke-test checklist (on device/emulator)**

1. App opens → HomeScreen shows "default" profile with RadioButton selected, FAB visible
2. Tap FAB → CONNECTING animation (loading circle), button disabled; then CONNECTED (green FAB)
3. Tap FAB again → STOPPING animation; then STOPPED
4. Tap "+" → AddProfileDialog appears, pre-filled "Config 1"; create → navigate to editor; back → list shows "Config 1"
5. Tap "+" → AddProfileDialog; type "Config 1" → create button disabled, "名称已存在" shown
6. Tap "›" on a profile → editor opens with profile name in TopAppBar, delete icon visible
7. With only 1 profile, delete icon disabled; add a second profile, then delete icon enabled
8. Delete a profile → confirm dialog → delete → navigates back to HomeScreen, list updated
9. Delete active profile → switches active to first in list automatically
10. Start VPN → RadioButtons disabled; stop VPN → RadioButtons re-enabled
11. Start VPN → RadioButtons disabled → navigate to editor → delete icon disabled
12. Error state (revoke VPN permission while connected) → Snackbar shows error; FAB error color; RadioButton enabled for profile switch

- [ ] **Step 3: Commit if any last-minute fixes were made**

```bash
cd android && git add -A && git commit -m "fix: post-integration cleanup

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Spec coverage check

| Spec requirement | Task |
|---|---|
| Profile list on HomeScreen with RadioButton (left) + chevron (right) | Task 8 |
| Profile name, default "Config N" naming | Task 1 (`getNextDefaultName`) |
| RadioButton activates profile; disabled when VPN running | Tasks 6, 8 |
| Tap chevron → editor screen | Tasks 8, 9 |
| Editor supports delete | Tasks 4, 5 |
| FAB (right-bottom) for start/stop, uses ic_tile_vpn | Task 8 |
| FAB loading animation during CONNECTING/STOPPING | Tasks 2, 8 |
| FAB disabled during transitions | Task 8 |
| "+" button in TopAppBar → AddProfileDialog | Tasks 7, 8 |
| Duplicate name validation | Tasks 1, 7 |
| Delete disabled with 1 profile or VPN running | Tasks 4, 5 |
| Delete active profile → switch to first | Task 1 |
| Reactive profile list (no stale data after nav) | Task 1 (`profilesFlow`), Task 6 (`combine`) |
| Error/listen-address shown via Snackbar | Task 8 |
