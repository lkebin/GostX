# Per-App Proxy Design

**Date:** 2026-06-01  
**Branch:** proxy-by-app  
**Feature:** 分应用代理（黑名单 / 白名单）

---

## Overview

Allow users to configure per-app VPN routing from the Settings screen. A single app list is maintained, and a mode toggle (blacklist / whitelist) determines how it is applied:

- **Blacklist mode**: apps in the list bypass the VPN; all others go through it.
- **Whitelist mode**: only apps in the list go through the VPN; all others bypass it.

---

## Scope

- Android only (uses `VpnService.Builder` APIs).
- Only user-installed (non-system) apps are shown in the picker.
- Settings take effect on the next VPN start/restart.

---

## Data Layer

### ConfigRepository extensions

Two new fields added to `ConfigRepository` (stored in `SharedPreferences`):

| Field | Type | Key | Default |
|---|---|---|---|
| `appFilterMode` | `AppFilterMode` (enum) | `app_filter_mode` | `BLACKLIST` |
| `appFilterList` | `Set<String>` | `app_filter_packages` | empty set |

`AppFilterMode` enum:
```kotlin
enum class AppFilterMode { BLACKLIST, WHITELIST }
```

Both fields exposed as `StateFlow` (`appFilterModeFlow`, `appFilterListFlow`) for reactive UI updates.

Storage: `AppFilterMode` serialized as `"blacklist"` / `"whitelist"` string; package set stored as Android native `StringSet`.

---

## Service Layer

### GostVpnService — VPN builder logic

`addAllowedApplication` and `addDisallowedApplication` are **mutually exclusive** on a single `Builder` instance (mixing them throws `UnsupportedOperationException`). The logic must use only one set of calls per VPN session:

```kotlin
val filterList = configRepo.appFilterList
val filterMode = configRepo.appFilterMode
val stalePackages = mutableSetOf<String>()

when (filterMode) {
    AppFilterMode.BLACKLIST -> {
        // Always exclude self to prevent the tun2socks→gost→tun loop
        builder.addDisallowedApplication(packageName)
        filterList.forEach { pkg ->
            try { builder.addDisallowedApplication(pkg) }
            catch (e: PackageManager.NameNotFoundException) {
                log("Removing uninstalled package from blacklist: $pkg")
                stalePackages += pkg
            }
        }
    }
    AppFilterMode.WHITELIST -> {
        // Self is not in the whitelist → automatically bypasses VPN (loop prevented)
        // Empty list: all apps go through VPN (addAllowedApplication never called)
        filterList.forEach { pkg ->
            try { builder.addAllowedApplication(pkg) }
            catch (e: PackageManager.NameNotFoundException) {
                log("Removing uninstalled package from whitelist: $pkg")
                stalePackages += pkg
            }
        }
    }
}
if (stalePackages.isNotEmpty()) {
    configRepo.appFilterList = filterList - stalePackages
}
```

> **Key insight:** In whitelist mode, the app's own package need not be explicitly excluded — it is not in the allowed list and therefore automatically bypasses the VPN, preventing the routing loop.

---

## UI Layer

### Settings screen changes

New section added to `SettingsScreen` below the existing logging toggle:

```
┌─────────────────────────────────────────────┐
│  分应用代理                   [管理应用 →]    │
│  [黑名单] ○──● [白名单]                      │
│  重启 VPN 后生效（小字提示）                  │
└─────────────────────────────────────────────┘
```

- Mode toggle: two mutually exclusive `FilterChip` / `SegmentedButton` components bound to `appFilterMode`.
- "管理应用" entry: tappable row / button navigating to `AppFilterScreen`.
- Selected app count shown as subtitle (e.g. "3 个应用").
- "重启 VPN 后生效" hint shown when VPN is currently running.

### App filter screen — new file: `ui/settings/AppFilterScreen.kt`

```
← 分应用代理                            [完成]
──────────────────────────────────────
🔍  搜索应用名...
──────────────────────────────────────
[icon]  微信           com.tencent.mm     ☑
[icon]  支付宝         com.eg.android…    ☐
[icon]  Chrome         com.android.chr…   ☑
...
```

- Search bar at top; filters by app name in real time.
- Each row: app icon + name + package name (secondary text) + checkbox.
- "完成" button saves and pops back.
  - **Whitelist mode + empty selection**: "完成" is disabled; inline prompt shown: "白名单模式至少选择一个应用".
- Blacklist mode allows empty list (means no filtering — all apps use VPN).

### App filter view model — new file: `ui/settings/AppFilterViewModel.kt`

- Loads installed user apps on `Dispatchers.IO` via `PackageManager.getInstalledApplications(GET_META_DATA)`, filtered to exclude system apps (`ApplicationInfo.FLAG_SYSTEM`).
- Exposes `uiState: StateFlow<AppFilterUiState>` containing the sorted app list, current selections, search query, and loading flag.
- Persists changes to `ConfigRepository` on "完成".

### Navigation

`Navigation.kt`: add `appFilter` route, pass `ConfigRepository` (or ViewModel) as dependency.

---

## Error Handling

| Scenario | Handling |
|---|---|
| Package in list has been uninstalled | Caught with `NameNotFoundException` per-package; automatically removed from `appFilterList` in `ConfigRepository`; VPN continues starting |
| Whitelist mode with empty list | UI prevents saving; "完成" button disabled with explanatory prompt |
| App list loading takes time | `AppFilterViewModel` shows loading state while fetching on IO dispatcher |
| Settings changed while VPN is running | Settings take effect on next VPN start; "重启 VPN 后生效" hint shown in Settings |

---

## Files Changed / Created

| File | Change |
|---|---|
| `data/ConfigRepository.kt` | Add `appFilterMode`, `appFilterList`, `appFilterModeFlow`, `appFilterListFlow` |
| `service/GostVpnService.kt` | Update VPN builder to apply filter list |
| `ui/settings/SettingsScreen.kt` | Add mode toggle + "管理应用" entry |
| `ui/settings/SettingsViewModel.kt` | Expose filter mode and list flows |
| `ui/settings/AppFilterScreen.kt` | **New** — app picker screen |
| `ui/settings/AppFilterViewModel.kt` | **New** — loads installed apps, manages selections |
| `ui/Navigation.kt` | Add `appFilter` route |
| `res/values/strings.xml` | Add string resources for new UI text |

---

## Out of Scope

- iOS / macOS platforms
- System app filtering
- Per-profile filter settings (one global filter applies to all profiles)
- Live apply without VPN restart
