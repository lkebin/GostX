# Settings Unsaved Changes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Save/Discard/Cancel confirmation prompts when leaving a YAML or file editor with unsaved changes in the macOS Settings window.

**Architecture:** Elevate dirty-state tracking from editor views to `SettingsView` via `@Binding`, intercept navigation changes with computed `Binding` wrappers, and add a `NSWindowDelegate` shim for window close. File-to-file switching within the Files tab is handled locally with `NSAlert.runModal()`.

**Tech Stack:** Swift 5, SwiftUI, AppKit (NSWindowDelegate, NSAlert)

## Global Constraints

- macOS 14.0+ deployment target (all views annotated `@available(macOS 14.0, *)`)
- Use `NSLocalizedString` for all user-facing strings
- Follow existing project patterns (NavigationSplitView, @StateObject, @Binding)
- All new strings must be added to both `en.lproj/Localizable.stringsdict` and `zh-Hans.lproj/Localizable.stringsdict`

---

### Task 1: Expose YAML editor dirty state and text via @Binding

**Files:**
- Modify: `macos/GostX/YamlEditorView.swift`

**Interfaces:**
- Consumes: `profileId: String`, `ConfigRepository.shared`
- Produces: `@Binding var isDirty: Bool`, `@Binding var yamlText: String`

- [ ] **Step 1: Change `isDirty` to @Binding, `yamlText` to @Binding**

Replace `@State private var isDirty = false` and `@State private var yamlText: String = ""` with `@Binding` equivalents:

```swift
struct YamlEditorView: View {
    let profileId: String
    @Binding var isDirty: Bool
    @Binding var yamlText: String
    @StateObject private var repo = ConfigRepository.shared
    @State private var originalText: String = ""
```

- [ ] **Step 2: Remove the `@State` initializations and update body**

The `.onChange(of: yamlText)` and `.onAppear` logic stays the same — they now write to the bindings instead of local state:

```swift
var body: some View {
    VStack(spacing: 0) {
        YamlTextView(text: $yamlText)
            .onChange(of: yamlText) { newValue in
                isDirty = newValue != originalText
            }
            .onAppear {
                let text = repo.getConfig(profileId)
                yamlText = text
                originalText = text
                isDirty = false
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        // ... rest unchanged
    }
}
```

- [ ] **Step 3: Verify the file compiles**

Run: `cd macos && xcodebuild -scheme GostX -destination 'platform=macOS' build 2>&1 | tail -20`

Expected: Build succeeds (or the only errors are from other files not updated yet)

---

### Task 2: Add custom bindings and confirmation alert to SettingsView

**Files:**
- Modify: `macos/GostX/SettingsView.swift`

**Interfaces:**
- Consumes: `$isEditorDirty`, `$yamlText` from `YamlEditorView`, `fileVM.isFileDirty` from `FileManageViewModel`
- Produces: confirmation alert for category/profile switch

- [ ] **Step 1: Add new @State variables**

After existing `@State` declarations:

```swift
@State private var isEditorDirty = false
@State private var yamlText = ""
@State private var pendingCategory: SettingsCategory? = nil
@State private var pendingProfileId: String? = nil
@State private var showDiscardAlert = false
```

- [ ] **Step 2: Rename existing selection state and add computed bindings**

Rename `selectedCategory` → `_selectedCategory`, `selectedProfileId` → `_selectedProfileId` in `@State` declarations. Then add computed bindings:

```swift
@State private var _selectedCategory: SettingsCategory = .profiles
@State private var _selectedProfileId: String? = nil
@State private var isEditorDirty = false
@State private var yamlText = ""
@State private var pendingCategory: SettingsCategory? = nil
@State private var pendingProfileId: String? = nil
@State private var showDiscardAlert = false

// Keep old property names for code that reads them (switch statements etc)
private var selectedCategory: SettingsCategory { _selectedCategory }
private var selectedProfileId: String? { _selectedProfileId }

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

private var profileIdBinding: Binding<String?> {
    Binding(
        get: { _selectedProfileId },
        set: { newValue in
            if isCurrentEditorDirty && newValue != _selectedProfileId {
                pendingProfileId = newValue
                showDiscardAlert = true
            } else {
                _selectedProfileId = newValue
            }
        }
    )
}

private var isCurrentEditorDirty: Bool {
    switch _selectedCategory {
    case .profiles: return _selectedProfileId != nil && isEditorDirty
    case .files: return fileVM.selectedFileName != nil && fileVM.isFileDirty
    case .logs: return false
    }
}

private func saveCurrentEditor() {
    switch _selectedCategory {
    case .profiles:
        if let id = _selectedProfileId {
            ConfigRepository.shared.saveConfig(id, yaml: yamlText)
            if id == ConfigRepository.shared.activeProfileId {
                AppGroupConfig.writeYaml(yamlText)
            }
        }
    case .files:
        fileVM.saveFileContent()
    case .logs:
        break
    }
}

private func applyPendingNavigation() {
    if let cat = pendingCategory {
        _selectedCategory = cat
        pendingCategory = nil
    }
    if let pid = pendingProfileId {
        _selectedProfileId = pid
        pendingProfileId = nil
    }
}
```

- [ ] **Step 3: Update sidebar List to use categoryBinding**

Replace:
```swift
List(selection: $selectedCategory) {
```
With:
```swift
List(selection: categoryBinding) {
```

- [ ] **Step 4: Update ProfileListView to use profileIdBinding**

Replace:
```swift
ProfileListView(selectedProfileId: $selectedProfileId)
```
With:
```swift
ProfileListView(selectedProfileId: profileIdBinding)
```

- [ ] **Step 5: Update profile detail to pass bindings**

Replace the `case .profiles` detail block:

```swift
case .profiles:
    if let profileId = selectedProfileId {
        YamlEditorView(profileId: profileId, isDirty: $isEditorDirty, yamlText: $yamlText)
            .id(profileId)
            .ignoresSafeArea(.container, edges: .top)
    } else {
        placeholderView(icon: "doc.text.magnifyingglass",
            text: NSLocalizedString("Select a profile to edit", comment: ""))
    }
```

- [ ] **Step 6: Add the confirmation alert modifier**

Add to the `NavigationSplitView` modifier chain:

```swift
.alert(NSLocalizedString("Unsaved Changes", comment: ""),
       isPresented: $showDiscardAlert) {
    Button(NSLocalizedString("Save", comment: "")) {
        saveCurrentEditor()
        applyPendingNavigation()
    }
    Button(NSLocalizedString("Discard", comment: "")) {
        applyPendingNavigation()
    }
    Button(NSLocalizedString("Cancel", comment: ""), role: .cancel) {
        pendingCategory = nil
        pendingProfileId = nil
    }
} message: {
    Text(NSLocalizedString("Do you want to save the changes you made before leaving?", comment: ""))
}
```

- [ ] **Step 7: Update all switch/case statements**

The `switch selectedCategory` statements that already exist should use `_selectedCategory` directly since `selectedCategory` is now a computed property that reads `_selectedCategory`. These require no change because `selectedCategory` returns `_selectedCategory`. But verify each use references `selectedCategory` (the computed property) not a local shadow.

- [ ] **Step 8: Verify the file compiles**

Run build and fix any issues.

---

### Task 3: Intercept file-to-file switching in FileListView

**Files:**
- Modify: `macos/GostX/FileListView.swift`

**Interfaces:**
- Consumes: `vm.isFileDirty`, `vm.saveFileContent()`, `vm.selectFile(_:)`

- [ ] **Step 1: Replace the file selection Binding with dirty-checked version**

In `FileListView.swift`, find the `List(selection: Binding(...))` block and replace the setter:

```swift
List(selection: Binding(
    get: { vm.selectedFileName },
    set: { name in
        if let name, vm.isFileDirty, name != vm.selectedFileName {
            let alert = NSAlert()
            alert.messageText = NSLocalizedString("Unsaved Changes", comment: "")
            alert.informativeText = NSLocalizedString("Do you want to save the changes you made to this file?", comment: "")
            alert.addButton(withTitle: NSLocalizedString("Save", comment: ""))
            alert.addButton(withTitle: NSLocalizedString("Discard", comment: ""))
            alert.addButton(withTitle: NSLocalizedString("Cancel", comment: ""))
            switch alert.runModal() {
            case .alertFirstButtonReturn: // Save
                vm.saveFileContent()
                vm.selectFile(name)
            case .alertSecondButtonReturn: // Discard
                vm.selectFile(name)
            default: // Cancel
                break
            }
        } else {
            DispatchQueue.main.async {
                if let name {
                    vm.selectFile(name)
                } else {
                    vm.selectedFileName = nil
                    vm.fileContent = ""
                }
            }
        }
    }
))
```

- [ ] **Step 2: Verify the file compiles**

---

### Task 4: Add window close interception

**Files:**
- Modify: `macos/GostX/SettingsView.swift`

**Interfaces:**
- Consumes: `isCurrentEditorDirty`, `saveCurrentEditor()`
- Produces: `WindowCloseHandler` NSViewRepresentable

- [ ] **Step 1: Add WindowCloseHandler representable**

Add before `SettingsView` or at the bottom of the file:

```swift
// MARK: - Window Close Handler

/// Intercepts window close to prompt for unsaved changes.
private struct WindowCloseHandler: NSViewRepresentable {
    let isDirty: Bool
    let saveHandler: (() -> Void)?

    class Coordinator: NSObject, NSWindowDelegate {
        var onClose: (() -> Bool)?

        func windowShouldClose(_ sender: NSWindow) -> Bool {
            onClose?() ?? true
        }
    }

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        DispatchQueue.main.async {
            view.window?.delegate = context.coordinator
        }
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        context.coordinator.onClose = { [isDirty, saveHandler] in
            guard isDirty else { return true }
            let alert = NSAlert()
            alert.messageText = NSLocalizedString("Unsaved Changes", comment: "")
            alert.informativeText = NSLocalizedString("Do you want to save the changes you made before closing?", comment: "")
            alert.addButton(withTitle: NSLocalizedString("Save", comment: ""))
            alert.addButton(withTitle: NSLocalizedString("Discard", comment: ""))
            alert.addButton(withTitle: NSLocalizedString("Cancel", comment: ""))
            switch alert.runModal() {
            case .alertFirstButtonReturn:
                saveHandler?()
                return true
            case .alertSecondButtonReturn:
                return true
            default:
                return false
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }
}
```

- [ ] **Step 2: Attach WindowCloseHandler to the view hierarchy**

Add as a modifier to the `NavigationSplitView`:

```swift
.background(WindowCloseHandler(
    isDirty: isCurrentEditorDirty,
    saveHandler: { [isCurrentEditorDirty, saveCurrentEditor] in
        if isCurrentEditorDirty { saveCurrentEditor() }
    }
))
```

Note: the closure captures by value — since `updateNSView` is called on every re-render, the closure always has the latest `isCurrentEditorDirty` result and `saveCurrentEditor` function.

- [ ] **Step 3: Verify the file compiles**

---

### Task 5: Add new localized strings

**Files:**
- Modify: `macos/GostX/en.lproj/Localizable.stringsdict`
- Modify: `macos/GostX/zh-Hans.lproj/Localizable.stringsdict`

- [ ] **Step 1: Add English strings**

```xml
<!-- Settings unsaved changes -->
<string name="Unsaved Changes">Unsaved Changes</string>
<string name="Do you want to save the changes you made before leaving?">Do you want to save the changes you made before leaving?</string>
<string name="Do you want to save the changes you made to this file?">Do you want to save the changes you made to this file?</string>
<string name="Do you want to save the changes you made before closing?">Do you want to save the changes you made before closing?</string>
```

Wait — these are `.stringsdict` files (property list format), not `.strings`. Let me check the existing format.

- [ ] **Step 1: Add English strings**

Read `en.lproj/Localizable.stringsdict` to confirm format, then add the new entries.

- [ ] **Step 2: Add Chinese translations**

Read `zh-Hans.lproj/Localizable.stringsdict` and add corresponding Chinese entries.

---

### Task 6: Build and verify

**Files:** N/A

- [ ] **Step 1: Full build**

```bash
cd macos && xcodebuild -scheme GostX -destination 'platform=macOS' build 2>&1 | tail -30
```

Expected: Build succeeds with no errors or warnings from modified files.

- [ ] **Step 2: Manual smoke test**

Launch the app, open Settings, and verify:
1. Edit YAML profile → switch profile → prompt appears
2. Tap Save → profile saved, navigated to new profile
3. Edit YAML profile → switch category to Files → prompt appears
4. Tap Discard → navigated to Files, changes lost
5. Tap Cancel → stays on current view
6. Edit file in Files tab → switch to another file → prompt appears
7. Edit YAML → close window → prompt appears
8. Edit file → close window → prompt appears
9. No prompt when no unsaved changes (normal navigation works)

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2025-07-20-settings-unsaved-changes-design.md
git add docs/superpowers/plans/2025-07-20-settings-unsaved-changes.md
git add macos/GostX/SettingsView.swift macos/GostX/YamlEditorView.swift macos/GostX/FileListView.swift
git add macos/GostX/en.lproj/Localizable.stringsdict macos/GostX/zh-Hans.lproj/Localizable.stringsdict
git commit -m "feat: add unsaved changes prompt for macOS settings window"
```
