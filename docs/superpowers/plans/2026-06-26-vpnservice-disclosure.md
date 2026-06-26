# VpnService Disclosure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a first-launch VPN disclosure dialog that gets explicit user consent before starting the VPN tunnel, per Google Play VpnService policy.

**Architecture:** HomeViewModel reads a SharedPreferences flag `vpn_disclosure_accepted` and exposes a `showVpnDisclosureDialog` StateFlow. When false, toggleVpn() sets showVpnDisclosureDialog=true instead of starting the service. HomeScreen renders an AlertDialog whose accept button calls acceptVpnDisclosure() which saves the flag and triggers the actual VPN start.

**Tech Stack:** Kotlin, Jetpack Compose, Material3 AlertDialog, SharedPreferences, StateFlow

---

### Task 1: Add string resources (English)

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add disclosure dialog strings**

Add to `strings.xml` right before the closing `</resources>` tag:

```xml
    <!-- VPN disclosure dialog -->
    <string name="vpn_disclosure_title">VPN Connection Notice</string>
    <string name="vpn_disclosure_message">GostX will create a VPN connection to route your network traffic.\n\n• Uses Android VpnService to create a local VPN tunnel\n• All traffic is routed through your configured proxy chains\n• No personal data is collected or transmitted\n• Traffic encryption is provided by your chosen proxy protocols</string>
    <string name="vpn_disclosure_accept">Accept &amp; Start</string>
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/res/values/strings.xml
git commit -m "feat: add VPN disclosure dialog strings (en)"
```

---

### Task 2: Add string resources (Chinese)

**Files:**
- Modify: `android/app/src/main/res/values-zh/strings.xml`

- [ ] **Step 1: Add disclosure dialog strings**

Add to `strings.xml` right before the closing `</resources>` tag:

```xml
    <!-- VPN disclosure dialog -->
    <string name="vpn_disclosure_title">VPN 连接说明</string>
    <string name="vpn_disclosure_message">GostX 将创建 VPN 连接以路由您的网络流量。\n\n• 使用 Android VpnService 创建本地 VPN 隧道\n• 所有流量通过您配置的代理链进行路由\n• 不收集或传输任何个人数据\n• 流量加密由您选择的代理协议提供</string>
    <string name="vpn_disclosure_accept">同意并启动</string>
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/res/values-zh/strings.xml
git commit -m "feat: add VPN disclosure dialog strings (zh)"
```

---

### Task 3: Add disclosure state to HomeViewModel

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/home/HomeViewModel.kt`

- [ ] **Step 1: Inject disclosure preference into HomeViewModel constructor and expose state**

Replace the current `HomeViewModel` class definition (lines 39-127) with the updated version. The key additions are:
- Take `SharedPreferences` as a constructor parameter instead of creating it inline
- Add `_showVpnDisclosureDialog` MutableStateFlow
- Add `showVpnDisclosureDialog` StateFlow
- Add `acceptVpnDisclosure()` method
- Intercept `VpnToggleAction.START` in `toggleVpn()` to check disclosure

```kotlin
class HomeViewModel(
    app: Application,
    private val repo: ConfigRepository
) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE)

    val vpnState = GlobalVpnState.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, GlobalVpnState.state.value)

    val loggingEnabled: StateFlow<Boolean> = repo.loggingEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, repo.loggingEnabled)

    private val _batteryOptimizationNeeded = MutableStateFlow(false)
    val batteryOptimizationNeeded: StateFlow<Boolean> = _batteryOptimizationNeeded

    private val _showVpnDisclosureDialog = MutableStateFlow(false)
    val showVpnDisclosureDialog: StateFlow<Boolean> = _showVpnDisclosureDialog

    private val vpnDisclosureAccepted: Boolean
        get() = prefs.getBoolean("vpn_disclosure_accepted", false)

    init {
        checkBatteryOptimization()
    }

    /** Re-checks battery optimization status; call on every screen resume. */
    fun checkBatteryOptimization() {
        val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val dismissed = prefs.getBoolean("battery_opt_dismissed", false)
        _batteryOptimizationNeeded.value = !dismissed && !pm.isIgnoringBatteryOptimizations(applicationContext.packageName)
    }

    fun dismissBatteryOptimizationPrompt() {
        prefs.edit().putBoolean("battery_opt_dismissed", true).apply()
        _batteryOptimizationNeeded.value = false
    }

    fun openBatteryOptimizationSettings() {
        val ctx = getApplication<Application>()
        ctx.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${ctx.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    }

    private val refreshTrigger = MutableStateFlow(0)

    fun refresh() {
        refreshTrigger.value++
    }

    val homeState: StateFlow<HomeUiState> = combine(
        refreshTrigger,
        repo.profilesFlow,
        repo.activeProfileIdFlow
    ) { _, profiles, activeId -> HomeUiState(profiles = profiles, activeProfileId = activeId) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            HomeUiState(profiles = repo.profilesFlow.value, activeProfileId = repo.activeProfileIdFlow.value)
        )

    fun setActiveProfile(profileId: String) {
        if (!canSetActiveProfile(vpnState.value.status)) return
        repo.setActiveProfile(profileId)
    }

    /** Returns false if the name is already taken. */
    fun addProfile(name: String): String? = repo.addProfile(name)

    fun toggleVpn(onVpnPermissionRequired: () -> Unit = {}) {
        val ctx = getApplication<Application>()
        when (resolveVpnToggleAction(vpnState.value.status, VpnService.prepare(ctx) == null)) {
            VpnToggleAction.STOP -> {
                GlobalVpnState.setStopping()
                startService(ctx, GostVpnService.ACTION_STOP)
            }
            VpnToggleAction.START -> {
                if (vpnDisclosureAccepted) {
                    startService(ctx, GostVpnService.ACTION_START)
                } else {
                    _showVpnDisclosureDialog.value = true
                }
            }
            VpnToggleAction.REQUEST_PERMISSION -> onVpnPermissionRequired()
        }
    }

    fun acceptVpnDisclosure() {
        prefs.edit().putBoolean("vpn_disclosure_accepted", true).apply()
        _showVpnDisclosureDialog.value = false
        val ctx = getApplication<Application>()
        startService(ctx, GostVpnService.ACTION_START)
    }

    fun dismissVpnDisclosure() {
        _showVpnDisclosureDialog.value = false
    }

    private fun startService(ctx: Application, action: String) {
        val intent = Intent(ctx, GostVpnService::class.java).apply { this.action = action }
        if (action == GostVpnService.ACTION_START) ctx.startForegroundService(intent)
        else ctx.startService(intent)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/ui/home/HomeViewModel.kt
git commit -m "feat: add VPN disclosure consent gate to HomeViewModel"
```

---

### Task 4: Add disclosure dialog to HomeScreen

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/home/HomeScreen.kt`

- [ ] **Step 1: Add showDisclosureDialog collectAsState and dialog composable**

Two additions to `HomeScreen`:

**Addition A** — After the existing `var showAddDialog` line (line 94), add:

```kotlin
    val showDisclosureDialog by vm.showVpnDisclosureDialog.collectAsState()
```

**Addition B** — After the existing `if (showAddDialog)` block (lines 122-135), add the disclosure dialog block:

```kotlin
    if (showDisclosureDialog) {
        AlertDialog(
            onDismissRequest = { vm.dismissVpnDisclosure() },
            title = { Text(stringResource(R.string.vpn_disclosure_title)) },
            text = { Text(stringResource(R.string.vpn_disclosure_message)) },
            confirmButton = {
                TextButton(onClick = { vm.acceptVpnDisclosure() }) {
                    Text(stringResource(R.string.vpn_disclosure_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissVpnDisclosure() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
```

**Addition C** — Add the missing `AlertDialog` import at the top of the file (alongside other `material3` imports):

```kotlin
import androidx.compose.material3.AlertDialog
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/ui/home/HomeScreen.kt
git commit -m "feat: add VPN disclosure consent dialog to HomeScreen"
```

---

### Task 5: Verify build

- [ ] **Step 1: Build debug APK**

```bash
cd android && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run unit tests**

```bash
cd android && ./gradlew test
```

Expected: all tests pass
