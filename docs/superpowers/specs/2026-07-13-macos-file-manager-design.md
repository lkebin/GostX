# macOS File Manager for Bypass Files — Design Spec

**Date:** 2026-07-13
**Branch:** `macos`
**Reference:** Android `FileManageScreen` / `FileManageViewModel` / `FileRepository`

## Purpose

The macOS app runs in a sandbox. Users need a way to import, manage, and export
files used by gost's bypass feature (e.g., `bypass.file.path: china_ip_list.txt`).
These files must live in the App Group container so the Network Extension can
read them at runtime.

## Storage

```
group.cn.liukebin.gostx/
├── gost.yaml           # YAML config (existing)
├── gost.log             # log file (existing)
└── files/               # NEW: bypass file directory
    ├── china_ip_list.txt
    └── private_ip.txt
```

- `FileRepository` operates on `{container}/files/`
- `PacketTunnelProvider.setWorkDirAndLog()` sets `LibgostSetWorkDir` to
  `{container}/files/`, so gost configs reference bypass files by bare name
  (e.g. `bypass.file.path: china_ip_list.txt`)
- The `files/` directory is created on first use (both by `FileRepository`
  on import and by `PacketTunnelProvider` on tunnel start)

## UI Layout

The existing Settings window (`NavigationSplitView` with profile sidebar +
YAML editor detail) is wrapped in a **TabView** with two tabs:

```
┌─────────────────────────────────────────────┐
│ [Profiles]   [Files]                        │
├─────────────────────────────────────────────┤
│                                             │
│   当前 Tab 的内容                            │
│                                             │
└─────────────────────────────────────────────┘
```

- **Profiles Tab** — existing `NavigationSplitView` (unchanged)
- **Files Tab** — new `FileManageView`

### Files Tab Layout

A table/`List` view showing imported files:

| Column | Description |
|---|---|
| Name | File name |
| Size | Formatted size (B / KB / MB) |
| Modified | Last modified date (e.g. `yyyy-MM-dd HH:mm`) |

- **Empty state:** Centered placeholder text "No files. Click + to import."
- **Toolbar / bottom bar:** `+` button → `NSOpenPanel` for importing files
- **Per-file actions** (context menu / right-click or inline buttons):
  - Export → `NSSavePanel`
  - Rename → Sheet dialog with text field
  - Copy Path → copies full filesystem path to clipboard
  - Delete → confirmation alert, then delete

### Overwrite Handling

When importing a file whose name already exists in the container, a sheet
dialog asks the user to confirm overwrite or cancel.

## Components

### 1. `FileRepository` (new: `macos/GostX/FileRepository.swift`)

Pure data layer — operates on the `files/` subdirectory of the App Group container.

```swift
struct FileInfo: Identifiable {
    var id: String { name }
    let name: String
    let sizeBytes: Int64
    let lastModified: Date
}

class FileRepository {
    let workDir: URL  // {container}/files/

    init?()  // returns nil if container URL is unavailable

    func listFiles() -> [FileInfo]           // sorted by name, excludes dirs & dotfiles
    func exists(_ name: String) -> Bool
    func importFile(from sourceURL: URL) throws -> FileInfo  // copy to workDir
    func exportFile(_ name: String, to destURL: URL) throws
    func renameFile(_ oldName: String, to newName: String) throws
    func deleteFile(_ name: String) throws
    func filePath(_ name: String) -> String  // full filesystem path

    private func validateName(_ name: String) throws  // no "..", "/", empty
}
```

### 2. `FileManageViewModel` (new: `macos/GostX/FileManageView.swift`)

`ObservableObject` bridging `FileRepository` to the SwiftUI view.

- `@Published files: [FileInfo]`
- `@Published pendingOverwrite: (sourceURL: URL, fileName: String)?`
- `func refresh()`
- `func importFile(from:)` — detects overwrite, delegates to `confirmOverwrite()` or `doImport()`
- `func confirmOverwrite()` / `func cancelOverwrite()`
- `func renameFile(_:to:)` — validates, calls repo, shows error alert on failure
- `func deleteFile(_:)` — calls repo, refreshes
- `func exportFile(_:to:)` — calls repo
- `func copyPath(_:)` — copies `repo.filePath(name)` to `NSPasteboard`

Error messages are surfaced via `@Published var alertMessage: String?` or
similar alert-pattern state.

### 3. `FileManageView` (new: `macos/GostX/FileManageView.swift`)

SwiftUI view:

- `List` with `FileInfo` items
- `NSOpenPanel` for import, `NSSavePanel` for export
- Sheet for rename, alert for delete confirmation
- Sheets for import-overwrite confirmation
- Empty-state placeholder

### 4. `SettingsView` (modified: `macos/GostX/SettingsView.swift`)

Wraps existing content in a `TabView`:

```swift
TabView(selection: $selectedTab) {
    ProfilesTabView()
        .tabItem { Label("Profiles", systemImage: "doc.text") }
        .tag(0)
    FileManageView()
        .tabItem { Label("Files", systemImage: "folder") }
        .tag(1)
}
```

The existing `SettingsView` body becomes `ProfilesTabView`.

### 5. `PacketTunnelProvider` (modified: `macos/GostXTunnel/PacketTunnelProvider.swift`)

`setWorkDirAndLog()` updated to create and use the `files/` subdirectory:

```swift
private func setWorkDirAndLog() {
    guard let containerURL = AppGroupConfig.containerURL else { return }
    let filesDir = containerURL.appendingPathComponent("files")
    try? FileManager.default.createDirectory(at: filesDir,
        withIntermediateDirectories: true, attributes: nil)
    LibgostSetWorkDir(filesDir.path, nil)
    let logFile = containerURL.appendingPathComponent("gost.log").path
    LibgostSetLogFile(logFile, nil)
    LibgostSetLogLevel("info")
}
```

## Error Handling

| Scenario | Behavior |
|---|---|
| Container URL unavailable | `FileRepository.init?()` returns nil; ViewModel shows error state |
| `files/` directory doesn't exist | Created on first import or tunnel start |
| Import: source unreadable | Throw, show alert with error message |
| Import: duplicate name | Show overwrite confirmation sheet |
| Export: destination unwritable | Throw, show alert |
| Rename: invalid name (`..`, `/`, empty) | Validate, show "Invalid filename" alert |
| Rename: name collision | Show "File already exists" alert |
| Delete: file doesn't exist | No-op (idempotent) |
| Delete: target is a directory | Reject with error (shouldn't happen; `listFiles` excludes dirs) |

## Edge Cases

- **Multiple files with same name on import:** Handled by the overwrite-sheet one at a time (each import is a separate operation)
- **Very large files (>100MB):** `FileManager.copyItem` is synchronous; consider showing a progress indicator for large files (stretch goal)
- **Hidden files / macOS resource forks:** `listFiles()` excludes filenames starting with `.`; `._` AppleDouble files are invisible
- **Concurrent import + rename:** Each operation is synchronous on the main queue; no race conditions
- **Tunnel running during file import:** Fine — gost resolves file paths at connection time, and bypass matchers (e.g., `china_ip_list.txt`) are reloaded on config changes or restart

## Scope / Non-Goals

- No file preview / content viewer — just metadata
- No subdirectory support — flat file list only (matches Android)
- No drag-and-drop import (stretch goal)
- No progress indicator for large file imports (stretch goal)
- No file watch / auto-reload when bypass files change — user must restart gost

## Files Changed

| File | Change |
|---|---|
| `macos/GostX/FileRepository.swift` | **New** — data layer |
| `macos/GostX/FileManageView.swift` | **New** — ViewModel + View |
| `macos/GostX/SettingsView.swift` | Modified — add TabView wrapper |
| `macos/GostXTunnel/PacketTunnelProvider.swift` | Modified — `SetWorkDir` to `files/` |
| `macos/GostX.xcodeproj/project.pbxproj` | Modified — add new files to target |

## Testing

Manual verification checklist:

- [ ] Import a text file via NSOpenPanel; verify it appears in list
- [ ] Import a file with duplicate name; verify overwrite sheet appears
- [ ] Confirm overwrite; verify file is replaced
- [ ] Cancel overwrite; verify original file unchanged
- [ ] Rename a file; verify list updates
- [ ] Rename with invalid name (`..`, `/`, empty); verify error alert
- [ ] Rename to existing name; verify "already exists" alert
- [ ] Export a file via NSSavePanel; verify file saved to chosen location
- [ ] Delete a file; confirm dialog, verify file removed from list
- [ ] Copy path; verify pasteboard contains correct path
- [ ] Empty state displayed when no files exist
- [ ] Start VPN with a bypass config referencing an imported file; verify tunnel works
- [ ] `files/` directory auto-created on first import
- [ ] `files/` directory auto-created on tunnel start (in `setWorkDirAndLog`)
