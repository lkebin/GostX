# macOS Settings NavigationSplitView — Design

**Date:** 2025-07-16
**Status:** approved

## Summary

Replace the custom tab-bar layout in `SettingsView` with a standard macOS `NavigationSplitView` (3-column). The sidebar shows Profiles / Files / Logs categories. Selecting a category populates the Content column with that category's item list, and the Detail column shows the selected item's content.

## Current vs. Target

**Current**: Custom tab bar (Profiles / Files / Logs) at top; each tab is a self-contained view. Profiles has a left-right split, Files has a left-right split, Logs is a single scrollable view with inline controls.

**Target**: `NavigationSplitView` with three columns. The tab bar is replaced by a sidebar. Each category's content is split into list + detail panes. Logs gains a proper split between options (toggle, level) and content (scrollable log lines).

## Columns

| Sidebar | Content | Detail |
|---------|---------|--------|
| Profiles | Profile List (+ Add/Rename/Delete) | YAML Editor (or placeholder) |
| Files | File List (+ Import/New/… actions) | File Content Editor (or placeholder) |
| Logs | Log options (toggle, level picker) | Log Content (scrollable lines or empty) |

### Column behavior

- Always show all 3 columns with a minimum window width that accommodates them.
- When window is resized narrow, the sidebar hides behind the system toolbar toggle (default NavigationSplitView behavior).

## State Ownership

### Category selection — new `@State` in SettingsView

```swift
enum SettingsCategory: String, CaseIterable, Identifiable {
    case profiles, files, logs
    var id: Self { self }
}
@State private var selectedCategory: SettingsCategory = .profiles
```

### Profiles

- State stays in `ConfigRepository.shared` (no change).
- `selectedProfileId` lifted from tab-local `@State` into `SettingsView` so sidebar + content + detail share one selection binding.

### Files

- `FileManageViewModel` promoted from being created inside `FileManageView` to a `@StateObject` in `SettingsView`.
- Passed down to `FileListView` and `FileContentView`.
- `selectedFileName` binding flows through `FileManageViewModel`.

### Logs

- New `LogViewModel: ObservableObject` owned by `SettingsView`. Holds `lines`, `isFollowing`, timer, scroll proxy, plus `copyAll()` / `clearLog()`.
- Log options (`loggingEnabled`, `logLevel`) backed by `AppGroupConfig`, passed as bindings to `LogOptionsView`.
- `LogContentView` observes `LogViewModel.$lines` and `LogViewModel.$isFollowing`.

## Component Tree

```
SettingsView                                    // NavigationSplitView
├─ Sidebar                                       // List(selection: $selectedCategory)
│   ├─ Label("Profiles", "doc.text")
│   ├─ Label("Files", "folder")
│   └─ Label("Logs", "text.alignleft")
│
├─ Content (switches on selectedCategory)
│   ├─ .profiles → ProfileListView(repo, $selectedProfileId)
│   │               // List of profile names + Add/Rename/Delete context menu
│   ├─ .files    → FileListView(vm: FileManageViewModel)
│   │               // List of file names + Import/New/… + context menu
│   └─ .logs     → LogOptionsView($loggingEnabled, $logLevel)
│                   // Toggle + level picker + "Restart VPN to apply" hint
│
└─ Detail (switches on selectedCategory)
    ├─ .profiles → if let id → YamlEditorView(profileId: id)
    │               else → placeholder "Select a profile to edit"
    ├─ .files    → if let name → FileContentView(vm: FileManageViewModel)
    │               else → placeholder "Select a file to view"
    └─ .logs     → LogContentView(vm: LogViewModel)
                    // Scrollable log lines or "No logs" / "Logging is off"
```

## File Changes

| File | Change |
|------|--------|
| `SettingsView.swift` | Rewrite as NavigationSplitView orchestrator (~100 lines) |
| `FileManageView.swift` | Delete (gutted into `FileListView` + `FileContentView`) |
| `LogView.swift` | Delete (gutted into `LogOptionsView` + `LogContentView`) |
| New: `ProfileListView.swift` | Extracted from SettingsView profiles tab sidebar |
| New: `FileListView.swift` | Extracted from FileManageView sidebar |
| New: `FileContentView.swift` | Extracted from FileManageView detail pane |
| New: `LogOptionsView.swift` | Logging toggle + level picker |
| New: `LogContentView.swift` | Scrollable log lines + toolbar |
| New: `LogViewModel.swift` | ObservableObject for log state (lines, polling, copy, clear) |

## Data Flow

### Profiles
```
ConfigRepository.shared (@Published profiles, activeProfileId)
  → ProfileListView reads repo.profiles
  → tap sets selectedProfileId via Binding<ID?>
  → YamlEditorView(profileId:) reads/writes repo.getConfig()/repo.saveConfig()
  → Add/Rename/Delete call repo methods, refresh via @Published
```

### Files
```
FileManageViewModel (@StateObject in SettingsView, passes down)
  → FileListView reads vm.$files, binds vm.$selectedFileName
  → FileContentView read/write vm.fileContent via TextEditor binding
  → dirty tracking + save in FileManageViewModel
  → Import/Export/Rename/Delete stay in FileManageViewModel (methods + sheets)
```

### Logs
```
LogViewModel (@StateObject in SettingsView)
  → .lines, .isFollowing, timer, scrollProxy, .copyAll(), .clearLog()
LogOptionsView binds AppGroupConfig.loggingEnabled / logLevel
LogContentView observes logVM.$lines, logVM.$isFollowing
  → if lines.isEmpty && loggingEnabled → polling active, "No logs" placeholder
  → if lines.isEmpty && !loggingEnabled → "Logging is off" placeholder
  → else → ScrollViewReader with LazyVStack of monospaced lines
```

## Edge Cases

| Case | Handling |
|------|----------|
| No profiles exist | Profile list empty; "Add Profile" button visible; detail shows placeholder |
| Profile deleted while being edited | `selectedProfileId` set to next available (or nil); `YamlEditorView` guarded by `if let id` |
| Log file doesn't exist | `LogViewModel` returns empty array; detail shows "No logs" |
| Logging switched off | `LogViewModel` stops polling, clears lines, detail shows "Logging is off" |
| File deleted while being viewed | `selectedFileName` reset to nil by `deleteFile()`; detail falls to placeholder |
| Window resized narrow | NavigationSplitView collapses sidebar → system toggle button; Content + Detail remain |
| File save when unchanged | Save button disabled via `isFileDirty` (existing logic in ViewModel) |
| Rapid profile switching | `YamlEditorView.id(profileId)` forces full re-creation |

## Testing

- `LogViewModel` — new unit tests for: load from file, polling appends lines, clear empties file, copyAll joins lines
- `FileManageViewModel` — verify existing tests still pass; add coverage for `selectFile` / `saveFileContent` if missing
- SwiftUI views — manual QA confirms layout, navigation, and edge cases
