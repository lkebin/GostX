# Multi-Config Profile Support — Design Spec

**Date:** 2026-05-30
**Status:** Approved

---

## Overview

Redesign the Android app to support multiple named configuration profiles directly on the home screen, replacing the current single-config editor workflow. The home screen becomes the primary workspace: it shows the profile list and hosts the start/stop FAB. A dedicated editor screen handles YAML editing and profile deletion.

---

## Section 1: Data Model

### ConfigProfile

```kotlin
data class ConfigProfile(val id: String, val name: String)
```

`id == name` — names are unique, so the name serves as the stable identifier. This is compatible with the existing SharedPreferences key scheme (`config_profile_<id>`).

### ConfigRepository changes

| Method | Change |
|---|---|
| `getProfiles()` | Returns `List<ConfigProfile>` instead of `List<String>` |
| `addProfile(name: String): Boolean` | Creates a new profile with empty YAML content. Returns `false` if name already exists, `true` on success. |
| `getNextDefaultName(): String` | Returns the first unused name in the series "Config 1", "Config 2", … |
| `deleteProfile(profileId)` | If deleting the active profile, switch active to the first remaining profile. If deleting a non-active profile, active is unchanged. |

Existing SharedPreferences key format (`config_profile_list`, `config_active_profile`, `config_profile_<id>`) is unchanged — no migration needed for existing users.

### VpnStatus

Add `STOPPING` state to represent the in-progress stop sequence:

```
STOPPED → (start) → CONNECTING → CONNECTED → (stop) → STOPPING → STOPPED
                                                  ↓
                                                ERROR
```

`GlobalVpnState.setStopping()` is called at the entry of `GostVpnService.stopVpn()`.

---

## Section 2: Screen Layout

### HomeScreen

```
┌─────────────────────────────────┐
│  GostX              📋    ＋    │  ← TopAppBar: log icon + add-profile "+" icon
│─────────────────────────────────│
│                                 │
│  ○  Config 1               ›    │  ← RadioButton (left) activates profile
│  ●  Config 2               ›    │     ● = active; "›" chevron (right) → ConfigEdit
│  ○  Config 3               ›    │     RadioButton disabled when status is CONNECTING, STOPPING, or CONNECTED
│                                 │
│                        [VPN FAB]│  ← FloatingActionButton, bottom-right
└─────────────────────────────────┘     uses ic_tile_vpn drawable
```

**FAB state mapping:**

| VpnStatus | FAB appearance | Clickable |
|---|---|---|
| STOPPED | VPN icon, primary color | ✅ → start |
| CONNECTING | CircularProgressIndicator overlay | ❌ |
| CONNECTED | VPN icon, green (tertiary) color | ✅ → stop |
| STOPPING | CircularProgressIndicator overlay | ❌ |
| ERROR | VPN icon, error color | ✅ → retry start |

The listen address and error message are shown via a `Snackbar` when the VPN state changes; they do not occupy permanent space in the layout.

The ⚙️ config icon is removed from the TopAppBar — config access is now via the profile list items.

### ConfigScreen (editor)

```
┌─────────────────────────────────┐
│  ←  Config 2              💾 🗑 │  ← TopAppBar: back, save, delete
│─────────────────────────────────│
│  ┌───────────────────────────┐  │
│  │ services:                 │  │
│  │   - name: vpn             │  │  ← monospace YAML editor (unchanged)
│  │     ...                   │  │
│  └───────────────────────────┘  │
└─────────────────────────────────┘
```

- Profile chips removed (profile switching is on the home screen)
- Delete icon (🗑) disabled when only 1 profile remains or when VPN is running
- Tapping delete shows a confirmation `AlertDialog`; on confirm, pop back to home

### AddProfileDialog

Triggered by the "+" button in the HomeScreen TopAppBar.

```
┌─────────────────────────┐
│  新建配置                │
│  ┌─────────────────────┐│
│  │ Config 1            ││  ← pre-filled with getNextDefaultName()
│  └─────────────────────┘│
│  名称已存在               │  ← shown inline if duplicate
│              取消   创建  │  ← 创建 disabled when name is duplicate or blank
└─────────────────────────┘
```

On confirm: `repo.addProfile(name)` → navigate to `ConfigEdit(profileId = name)`.

---

## Section 3: Component & File Structure

### Modified files (9)

| File | Change summary |
|---|---|
| `data/ConfigRepository.kt` | Add `ConfigProfile`, `addProfile`, `getNextDefaultName`; update `getProfiles`, `deleteProfile` |
| `data/VpnStateRepository.kt` | Add `STOPPING` to `VpnStatus`; add `setStopping()` |
| `service/GostVpnService.kt` | Call `setStopping()` at start of `stopVpn()` |
| `ui/Navigation.kt` | Replace `Screen.Config` with `Screen.ConfigEdit(profileId)` → route `"config/{profileId}"` |
| `ui/home/HomeViewModel.kt` | Inject `ConfigRepository`; expose `profiles`, `activeProfileId`; add `setActiveProfile()`, `addProfile()` |
| `ui/home/HomeScreen.kt` | Full rewrite: profile `LazyColumn`, FAB, "+" TopAppBar action |
| `ui/config/ConfigScreen.kt` | Remove `FilterChip` row; add delete icon + confirmation dialog |
| `ui/config/ConfigViewModel.kt` | Single-profile editing; add `deleteProfile()`; emit nav event on delete |
| `MainActivity.kt` | Pass `configRepository` to `HomeScreen`; bind `ConfigEdit` route with profileId argument |

### New files (1)

| File | Purpose |
|---|---|
| `ui/home/AddProfileDialog.kt` | Name-input dialog with duplicate validation |

### Unchanged files

`LogScreen`, `LogViewModel`, `GostTileService`, `BootReceiver`, `NotificationHelper` — no changes required.

---

## Section 4: Error Handling & Edge Cases

### Profile list

| Case | Handling |
|---|---|
| All profiles deleted | Not possible: delete button disabled when only 1 profile remains |
| Duplicate name on add | AddProfileDialog disables "创建" and shows inline error |
| RadioButton tap during CONNECTING / STOPPING / CONNECTED | `enabled=false`, no-op |
| RadioButton tap in ERROR state | enabled — service is dead, switching config is safe |

### FAB (start/stop)

| Case | Handling |
|---|---|
| Tap during CONNECTING or STOPPING | FAB `enabled=false`, click ignored |
| Start failure (ERROR state) | Snackbar shows error message; FAB returns to clickable for retry |
| No VPN permission | Existing flow unchanged: system permission dialog |

### Profile deletion

| Case | Handling |
|---|---|
| Delete active profile | `ConfigRepository.deleteProfile` switches active to first remaining profile |
| Only 1 profile left | Delete icon disabled in editor TopAppBar |
| VPN running, user in editor | Can view/edit YAML freely; save works (takes effect on next start); delete disabled |

### Data compatibility

Existing SharedPreferences data from prior app versions (single `default` profile) is fully compatible — `getProfiles()` returns `[ConfigProfile("default", "default")]` and `getNextDefaultName()` starts at "Config 1".
