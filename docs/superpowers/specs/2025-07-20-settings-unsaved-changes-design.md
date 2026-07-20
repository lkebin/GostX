# Unsaved Changes Prompt for macOS Settings Window

**Date**: 2025-07-20
**Status**: Design (pending implementation)

## Problem

In the macOS Settings window, when a user modifies a profile's YAML config (or a file's content) and navigates away — by switching profiles, switching to a different settings category (Files/Logs), or closing the window — the unsaved changes are silently lost. There is no confirmation prompt.

## Design

Add a Save/Discard/Cancel confirmation dialog when the user attempts to leave an editor with unsaved changes.

### Scope

- **YAML editor** (Profile config) — dirty state tracked per-editor session
- **File editor** (FileContentView) — dirty state already tracked via `FileManageViewModel.isFileDirty`

### Approach: Custom Bindings + Confirmation Alert

#### 1. Elevate dirty state to SettingsView

**`YamlEditorView`**: Change `isDirty` from `@State private` to `@Binding var isDirty: Bool`. Change `yamlText` from `@State private` to `@Binding var yamlText: String`. This gives `SettingsView` ownership of both the dirty flag and the text content (needed for saving on behalf of the user when the prompt's "Save" button is pressed).

**`SettingsView`**: Add `@State private var isEditorDirty = false` and `@State private var yamlText = ""`. Pass these as bindings to `YamlEditorView`.

#### 2. Intercept navigation via custom bindings

Create computed `Binding<SettingsCategory>` and `Binding<String?>` that wrap the internal `@State` values. In the setter, check `isCurrentEditorDirty` before applying the change:

```swift
private var categoryBinding: Binding<SettingsCategory> {
    Binding(
        get: { _selectedCategory },
        set: { newValue in
            if isCurrentEditorDirty && newValue != _selectedCategory {
                pendingCategory = newValue
                showDiscardAlert = true
            } else {
                _selectedCategory = newValue
            }
        }
    )
}
```

Same pattern for profile selection.

The sidebar `List(selection: categoryBinding)` and `ProfileListView(selectedProfileId: profileIdBinding)` use these computed bindings instead of `$selectedCategory` / `$selectedProfileId`.

#### 3. Confirmation alert

When `showDiscardAlert` is true, present a `.alert` with three buttons:
- **Save** — Save the current editor's content, then apply the pending navigation
- **Discard** — Apply the pending navigation without saving
- **Cancel** — Clear pending navigation, stay on current view

The `isCurrentEditorDirty` computed property checks the active category:

```swift
private var isCurrentEditorDirty: Bool {
    switch _selectedCategory {
    case .profiles: return _selectedProfileId != nil && isEditorDirty
    case .files: return fileVM.selectedFileName != nil && fileVM.isFileDirty
    case .logs: return false
    }
}
```

#### 4. Window close interception

Add a minimal `NSViewRepresentable` (`WindowCloseHandler`) that sets itself as the window's `NSWindowDelegate`. On `windowShouldClose`, check `isCurrentEditorDirty` and show an `NSAlert` if dirty:

```swift
func windowShouldClose(_ sender: NSWindow) -> Bool {
    guard isDirty else { return true }
    let alert = NSAlert()
    alert.messageText = NSLocalizedString("Unsaved Changes", comment: "")
    // ...
    switch alert.runModal() {
    case .alertFirstButtonReturn: save(); return true  // Save
    case .alertSecondButtonReturn: return true          // Discard
    default: return false                                // Cancel
    }
}
```

The closure is updated on every view re-render via `updateNSView`, so it always captures the latest dirty state and save handler.

### Files Changed

| File | Change |
|------|--------|
| `macos/GostX/YamlEditorView.swift` | `isDirty` → `@Binding`, `yamlText` → `@Binding` |
| `macos/GostX/SettingsView.swift` | Custom bindings, alert, dirty tracking, `WindowCloseHandler` |
| `macos/GostX/FileListView.swift` | Intercept file selection in custom `Binding` setter when dirty |
| `macos/GostX/MacExtrasConfigurator.swift` | No changes needed |

### Edge Cases

- **No profile selected** → YAML editor placeholder visible, dirty state is always false → no prompt
- **No file selected** → File placeholder visible, dirty state is always false → no prompt
- **Category switch (Profiles↔Files)** → checked via custom category binding in `SettingsView`
- **Profile switch within Profiles** → checked via custom profileId binding in `SettingsView`
- **File switch within Files** → handled locally in `FileListView`'s file-selection `Binding` setter. When `vm.isFileDirty`, show an `NSAlert` with Save/Discard/Cancel options before calling `vm.selectFile(newName)`. Uses `NSAlert.runModal()` since the setter is not in a SwiftUI view builder context.
- **Window close while editing file** → `WindowCloseHandler` checks `fileVM.isFileDirty`
- **Save pressed on alert + YAML** → calls `repo.saveConfig(id, yaml: yamlText)` directly (text is accessible since `SettingsView` owns `yamlText` via binding)
- **Save pressed on alert + File** → calls `fileVM.saveFileContent()` (both from `SettingsView` alert and `FileListView`'s local NSAlert)
