# macOS File Manager for Bypass Files — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add file management to the macOS sandboxed app for importing/managing gost bypass files in the App Group container.

**Architecture:** `FileRepository` (data layer operating on `{container}/files/`) → `FileManageViewModel` (ObservableObject bridge) → `FileManageView` (SwiftUI). The existing SettingsView gets a TabView wrapper with Profiles and Files tabs. `PacketTunnelProvider` updates its work directory to `files/`.

**Tech Stack:** Swift 5.9+, SwiftUI, FileManager, App Group container, NSOpenPanel (via `.fileImporter`)

## Global Constraints

- App runs in macOS sandbox — file access must go through NSOpenPanel / `.fileImporter`
- Bypass files live in `{AppGroup container}/files/` — flat directory, no subdirectories
- gost configs reference files by bare name (e.g. `bypass.file.path: china_ip_list.txt`)
- Filenames must not contain `..`, `/`, or be empty
- Hidden files (`.` prefix) are excluded from listing
- `PacketTunnelProvider` creates `files/` dir on tunnel start if missing
- Match existing codebase style: repository in dedicated file, view in its own file, `@StateObject` for observation

---

## File Structure

| File | Responsibility |
|---|---|
| `macos/GostX/FileRepository.swift` (**new**) | Pure data layer: CRUD on `files/` directory in App Group container. `FileInfo` struct + `FileRepository` class. |
| `macos/GostX/FileManageView.swift` (**new**) | `FileManageViewModel` (ObservableObject) + `FileManageView` (SwiftUI). Import/export/rename/delete/copy-path UI. |
| `macos/GostX/SettingsView.swift` (modify) | Wrap existing body in a `TabView` with Profiles + Files tabs. |
| `macos/GostXTunnel/PacketTunnelProvider.swift` (modify) | Change `setWorkDirAndLog()` work dir from container root to `container/files/`. |
| `macos/GostX.xcodeproj/project.pbxproj` (modify) | Add `FileRepository.swift` and `FileManageView.swift` to the GostX target. |

---

### Task 1: FileRepository — Data Layer

**Files:**
- Create: `macos/GostX/FileRepository.swift`

**Interfaces:**
- Produces: `FileInfo` struct (Identifiable), `FileRepository` class with `listFiles()`, `exists(_:)`, `importFile(from:)`, `exportFile(_:to:)`, `renameFile(_:to:)`, `deleteFile(_:)`, `filePath(_:)`, `ensureDir()`

- [ ] **Step 1: Create `FileRepository.swift` with FileInfo and FileRepository**

```swift
// macos/GostX/FileRepository.swift
import Foundation

struct FileInfo: Identifiable {
    var id: String { name }
    let name: String
    let sizeBytes: Int64
    let lastModified: Date
}

class FileRepository {
    let workDir: URL

    init?() {
        guard let container = AppGroupConfig.containerURL else { return nil }
        workDir = container.appendingPathComponent("files")
        ensureDir()
    }

    // MARK: - Directory

    func ensureDir() {
        try? FileManager.default.createDirectory(at: workDir,
            withIntermediateDirectories: true, attributes: nil)
    }

    // MARK: - List

    func listFiles() -> [FileInfo] {
        guard let contents = try? FileManager.default.contentsOfDirectory(
            at: workDir,
            includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey],
            options: .skipsHiddenFiles
        ) else { return [] }
        return contents
            .filter { url in
                var isDir: ObjCBool = false
                FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir)
                return !isDir.boolValue
            }
            .compactMap { url in
                guard let attrs = try? url.resourceValues(forKeys: [
                    .fileSizeKey, .contentModificationDateKey
                ]) else { return nil }
                return FileInfo(
                    name: url.lastPathComponent,
                    sizeBytes: Int64(attrs.fileSize ?? 0),
                    lastModified: attrs.contentModificationDate ?? Date.distantPast
                )
            }
            .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    // MARK: - Exists

    func exists(_ name: String) -> Bool {
        guard let _ = try? validateName(name) else { return false }
        let url = workDir.appendingPathComponent(name)
        var isDir: ObjCBool = false
        return FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir)
            && !isDir.boolValue
    }

    // MARK: - Import

    @discardableResult
    func importFile(from sourceURL: URL) throws -> FileInfo {
        let name = sourceURL.lastPathComponent
        try validateName(name)
        let target = workDir.appendingPathComponent(name)
        // Remove existing file before copy
        if FileManager.default.fileExists(atPath: target.path) {
            try FileManager.default.removeItem(at: target)
        }
        try FileManager.default.copyItem(at: sourceURL, to: target)
        let attrs = try target.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
        return FileInfo(
            name: name,
            sizeBytes: Int64(attrs.fileSize ?? 0),
            lastModified: attrs.contentModificationDate ?? Date()
        )
    }

    // MARK: - Export

    func exportFile(_ name: String, to destURL: URL) throws {
        try validateName(name)
        let source = workDir.appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: source.path) else {
            throw FileRepositoryError.fileNotFound(name)
        }
        if FileManager.default.fileExists(atPath: destURL.path) {
            try FileManager.default.removeItem(at: destURL)
        }
        try FileManager.default.copyItem(at: source, to: destURL)
    }

    // MARK: - Rename

    func renameFile(_ oldName: String, to newName: String) throws {
        try validateName(oldName)
        let trimmed = newName.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else {
            throw FileRepositoryError.invalidName
        }
        try validateName(trimmed)
        let oldURL = workDir.appendingPathComponent(oldName)
        let newURL = workDir.appendingPathComponent(trimmed)
        guard FileManager.default.fileExists(atPath: oldURL.path) else {
            throw FileRepositoryError.fileNotFound(oldName)
        }
        if FileManager.default.fileExists(atPath: newURL.path) {
            throw FileRepositoryError.fileAlreadyExists(trimmed)
        }
        try FileManager.default.moveItem(at: oldURL, to: newURL)
    }

    // MARK: - Delete

    func deleteFile(_ name: String) throws {
        try validateName(name)
        let url = workDir.appendingPathComponent(name)
        if !FileManager.default.fileExists(atPath: url.path) { return }
        var isDir: ObjCBool = false
        FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir)
        guard !isDir.boolValue else {
            throw FileRepositoryError.notAFile(name)
        }
        try FileManager.default.removeItem(at: url)
    }

    // MARK: - Path

    func filePath(_ name: String) -> String {
        workDir.appendingPathComponent(name).path
    }

    // MARK: - Validation

    private func validateName(_ name: String) throws {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty || trimmed.contains("..") || trimmed.contains("/") {
            throw FileRepositoryError.invalidName
        }
    }
}

// MARK: - Errors

enum FileRepositoryError: LocalizedError {
    case invalidName
    case fileNotFound(String)
    case fileAlreadyExists(String)
    case notAFile(String)

    var errorDescription: String? {
        switch self {
        case .invalidName:
            return NSLocalizedString("Invalid filename.", comment: "")
        case .fileNotFound(let name):
            return String.localizedStringWithFormat(
                NSLocalizedString("File \"%@\" not found.", comment: ""), name)
        case .fileAlreadyExists(let name):
            return String.localizedStringWithFormat(
                NSLocalizedString("File \"%@\" already exists.", comment: ""), name)
        case .notAFile(let name):
            return String.localizedStringWithFormat(
                NSLocalizedString("\"%@\" is not a regular file.", comment: ""), name)
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add macos/GostX/FileRepository.swift
git commit -m "feat: add FileRepository data layer for bypass file management

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: FileManageViewModel — Observing Bridge

**Files:**
- Create: `macos/GostX/FileManageView.swift` (first part — ViewModel)

**Interfaces:**
- Consumes: `FileRepository` from Task 1
- Produces: `FileManageViewModel` class — `ObservableObject` with `@Published var files: [FileInfo]`, `@Published var alertMessage: String?`, `@Published var pendingOverwrite: (sourceURL: URL, fileName: String)?`, `func refresh()`, `func importFile(from:)`, `func confirmOverwrite()`, `func cancelOverwrite()`, `func renameFile(_:to:)`, `func deleteFile(_:)`, `func exportFile(_:to:)`, `func copyPath(_:)`

- [ ] **Step 1: Create FileManageViewModel at the top of `FileManageView.swift`**

```swift
// macos/GostX/FileManageView.swift
import SwiftUI
import AppKit

// MARK: - Helpers

private func formatFileSize(_ bytes: Int64) -> String {
    switch bytes {
    case 0..<1024: return "\(bytes) B"
    case 1024..<(1024 * 1024): return "\(bytes / 1024) KB"
    default: return String(format: "%.1f MB", Double(bytes) / (1024.0 * 1024.0))
    }
}

private func formatFileDate(_ date: Date) -> String {
    let fmt = DateFormatter()
    fmt.dateFormat = "yyyy-MM-dd HH:mm"
    return fmt.string(from: date)
}

// MARK: - ViewModel

@MainActor
class FileManageViewModel: ObservableObject {
    @Published var files: [FileInfo] = []
    @Published var alertMessage: String?
    @Published var pendingOverwrite: (sourceURL: URL, fileName: String)?

    private let repo: FileRepository?

    init() {
        repo = FileRepository()
        refresh()
    }

    var isAvailable: Bool { repo != nil }

    func refresh() {
        files = repo?.listFiles() ?? []
    }

    func importFile(from sourceURL: URL) {
        guard let repo else { return }
        let name = sourceURL.lastPathComponent
        if repo.exists(name) {
            pendingOverwrite = (sourceURL, name)
            return
        }
        doImport(sourceURL: sourceURL)
    }

    func confirmOverwrite() {
        guard let (url, _) = pendingOverwrite else { return }
        pendingOverwrite = nil
        doImport(sourceURL: url)
    }

    func cancelOverwrite() {
        pendingOverwrite = nil
    }

    private func doImport(sourceURL: URL) {
        guard let repo else { return }
        do {
            try repo.importFile(from: sourceURL)
            refresh()
        } catch {
            alertMessage = String.localizedStringWithFormat(
                NSLocalizedString("Import failed: %@", comment: ""),
                error.localizedDescription)
        }
    }

    func renameFile(_ oldName: String, to newName: String) {
        guard let repo else { return }
        do {
            try repo.renameFile(oldName, to: newName)
            refresh()
        } catch {
            alertMessage = error.localizedDescription
        }
    }

    func deleteFile(_ name: String) {
        guard let repo else { return }
        do {
            try repo.deleteFile(name)
            refresh()
        } catch {
            alertMessage = error.localizedDescription
        }
    }

    func exportFile(_ name: String, to destURL: URL) {
        guard let repo else { return }
        do {
            try repo.exportFile(name, to: destURL)
        } catch {
            alertMessage = error.localizedDescription
        }
    }

    func copyPath(_ name: String) {
        guard let repo else { return }
        let path = repo.filePath(name)
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(path, forType: .string)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add macos/GostX/FileManageView.swift
git commit -m "feat: add FileManageViewModel with import/export/rename/delete/copy-path

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: FileManageView — SwiftUI View

**Files:**
- Modify: `macos/GostX/FileManageView.swift` (append view to existing file)

**Interfaces:**
- Consumes: `FileManageViewModel` from Task 2
- Produces: `FileManageView` SwiftUI View

- [ ] **Step 1: Append FileManageView to `FileManageView.swift`**

Append the following after the ViewModel code from Task 2:

```swift
// MARK: - FileManageView

struct FileManageView: View {
    @StateObject private var vm = FileManageViewModel()
    @State private var showImporter = false
    @State private var showExporter = false
    @State private var exportName: String?
    @State private var renameTarget: FileInfo?
    @State private var renameText: String = ""
    @State private var deleteTarget: FileInfo?
    @State private var showDeleteConfirm = false

    var body: some View {
        if !vm.isAvailable {
            unavailableView
        } else if vm.files.isEmpty {
            emptyView
        } else {
            fileListView
        }
    }

    // MARK: - Unavailable

    private var unavailableView: some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 32))
                .foregroundColor(.secondary)
            Text("App Group container not available.")
                .font(.system(size: 13))
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Empty

    private var emptyView: some View {
        VStack(spacing: 16) {
            Image(systemName: "folder")
                .font(.system(size: 36))
                .foregroundColor(Color(nsColor: .tertiaryLabelColor))
            Text(NSLocalizedString("No files. Click + to import.", comment: ""))
                .font(.system(size: 13))
                .foregroundColor(.secondary)
            Button(action: { showImporter = true }) {
                Label(NSLocalizedString("Import File", comment: ""), systemImage: "plus")
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.data, .plainText, .text]) { result in
            if case .success(let url) = result {
                vm.importFile(from: url)
            }
        }
        .modifier(FileManageAlertModifier(vm: vm))
        .modifier(OverwriteSheetModifier(vm: vm))
    }

    // MARK: - List

    private var fileListView: some View {
        VStack(spacing: 0) {
            // Toolbar
            HStack {
                Text(NSLocalizedString("Bypass Files", comment: ""))
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(.secondary)
                Spacer()
                Button(action: { showImporter = true }) {
                    Image(systemName: "plus")
                        .font(.system(size: 12, weight: .medium))
                }
                .buttonStyle(.borderless)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)

            Divider()

            // File List
            List {
                ForEach(vm.files) { file in
                    FileRowView(
                        file: file,
                        onExport: {
                            exportName = file.name
                            showExporter = true
                        },
                        onRename: {
                            renameTarget = file
                            renameText = file.name
                        },
                        onCopyPath: { vm.copyPath(file.name) },
                        onDelete: {
                            deleteTarget = file
                            showDeleteConfirm = true
                        }
                    )
                }
            }
            .listStyle(.inset)
        }
        .frame(minWidth: 300, minHeight: 200)
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.data, .plainText, .text]) { result in
            if case .success(let url) = result {
                vm.importFile(from: url)
            }
        }
        .fileExporter(
            isPresented: $showExporter,
            item: exportName as? String ?? "",
            contentTypes: [.data],
            defaultFilename: exportName ?? "file"
        ) { result in
            if case .success(let url) = result, let name = exportName {
                vm.exportFile(name, to: url)
            }
            exportName = nil
        }
        .modifier(FileManageAlertModifier(vm: vm))
        .modifier(OverwriteSheetModifier(vm: vm))
        .modifier(FileManageDialogsModifier(
            renameTarget: $renameTarget,
            renameText: $renameText,
            deleteTarget: $deleteTarget,
            showDeleteConfirm: $showDeleteConfirm,
            vm: vm
        ))
    }
}

// MARK: - FileRowView

private struct FileRowView: View {
    let file: FileInfo
    let onExport: () -> Void
    let onRename: () -> Void
    let onCopyPath: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "doc.text")
                .font(.system(size: 16))
                .foregroundColor(.secondary)
                .frame(width: 20)

            VStack(alignment: .leading, spacing: 2) {
                Text(file.name)
                    .font(.system(size: 13))
                    .lineLimit(1)
                Text("\(formatFileSize(file.sizeBytes)) — \(formatFileDate(file.lastModified))")
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
            }

            Spacer()

            // Context menu via right-click
            Menu {
                Button(NSLocalizedString("Export...", comment: "")) { onExport() }
                Button(NSLocalizedString("Rename...", comment: "")) { onRename() }
                Button(NSLocalizedString("Copy Path", comment: "")) { onCopyPath() }
                Divider()
                Button(NSLocalizedString("Delete...", comment: ""), role: .destructive) { onDelete() }
            } label: {
                Image(systemName: "ellipsis.circle")
                    .font(.system(size: 12))
                    .foregroundColor(.secondary)
            }
            .menuStyle(.borderlessButton)
            .frame(width: 24)
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Alert Modifier

private struct FileManageAlertModifier: ViewModifier {
    @ObservedObject var vm: FileManageViewModel

    func body(content: Content) -> some View {
        content.alert(
            NSLocalizedString("Error", comment: ""),
            isPresented: Binding<Bool>(
                get: { vm.alertMessage != nil },
                set: { if !$0 { vm.alertMessage = nil } }
            ),
            actions: {
                Button(NSLocalizedString("OK", comment: "")) { vm.alertMessage = nil }
            },
            message: {
                Text(vm.alertMessage ?? "")
            }
        )
    }
}

// MARK: - Overwrite Sheet Modifier

private struct OverwriteSheetModifier: ViewModifier {
    @ObservedObject var vm: FileManageViewModel

    func body(content: Content) -> some View {
        content.sheet(isPresented: Binding<Bool>(
            get: { vm.pendingOverwrite != nil },
            set: { if !$0 { vm.cancelOverwrite() } }
        )) {
            VStack(spacing: 16) {
                Text(NSLocalizedString("File Already Exists", comment: ""))
                    .font(.headline)
                Text(String.localizedStringWithFormat(
                    NSLocalizedString("A file named \"%@\" already exists. Do you want to replace it?", comment: ""),
                    vm.pendingOverwrite?.fileName ?? ""))
                    .font(.system(size: 13))
                    .multilineTextAlignment(.center)
                    .frame(width: 300)
                HStack(spacing: 12) {
                    Button(NSLocalizedString("Cancel", comment: "")) { vm.cancelOverwrite() }
                        .keyboardShortcut(.cancelAction)
                    Button(NSLocalizedString("Replace", comment: "")) { vm.confirmOverwrite() }
                        .keyboardShortcut(.defaultAction)
                }
            }
            .padding()
            .frame(width: 340, height: 160)
        }
    }
}

// MARK: - Rename / Delete Dialogs Modifier

private struct FileManageDialogsModifier: ViewModifier {
    @Binding var renameTarget: FileInfo?
    @Binding var renameText: String
    @Binding var deleteTarget: FileInfo?
    @Binding var showDeleteConfirm: Bool
    let vm: FileManageViewModel

    func body(content: Content) -> some View {
        content
            // Rename sheet
            .sheet(isPresented: Binding<Bool>(
                get: { renameTarget != nil },
                set: { if !$0 { renameTarget = nil } }
            )) {
                VStack(spacing: 16) {
                    Text(NSLocalizedString("Rename File", comment: "")).font(.headline)
                    TextField(NSLocalizedString("File name", comment: ""), text: $renameText)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 250)
                    HStack {
                        Button(NSLocalizedString("Cancel", comment: "")) { renameTarget = nil }
                            .keyboardShortcut(.cancelAction)
                        Button(NSLocalizedString("Rename", comment: "")) {
                            if let target = renameTarget, !renameText.trimmingCharacters(in: .whitespaces).isEmpty {
                                vm.renameFile(target.name, to: renameText)
                                renameTarget = nil
                            }
                        }
                        .keyboardShortcut(.defaultAction)
                        .disabled(renameText.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
                .padding()
                .frame(width: 320, height: 150)
            }
            // Delete confirmation alert
            .alert(
                NSLocalizedString("Delete File", comment: ""),
                isPresented: $showDeleteConfirm,
                presenting: deleteTarget
            ) { file in
                Button(NSLocalizedString("Cancel", comment: ""), role: .cancel) {
                    deleteTarget = nil
                }
                Button(NSLocalizedString("Delete", comment: ""), role: .destructive) {
                    vm.deleteFile(file.name)
                    deleteTarget = nil
                }
            } message: { file in
                Text(String.localizedStringWithFormat(
                    NSLocalizedString("Are you sure you want to delete \"%@\"? This cannot be undone.", comment: ""),
                    file.name))
            }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add macos/GostX/FileManageView.swift
git commit -m "feat: add FileManageView with file list, import, export, rename, delete dialogs

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: SettingsView — Add TabView Wrapper

**Files:**
- Modify: `macos/GostX/SettingsView.swift`

**Interfaces:**
- Consumes: `FileManageView` from Task 3
- Produces: Modified `SettingsView` with TabView wrapping Profiles and Files tabs

- [ ] **Step 1: Modify `SettingsView` body to wrap in TabView**

Replace the entire body of `struct SettingsView` with a TabView wrapper. The existing `NavigationSplitView` content becomes the Profiles tab.

Edit `SettingsView.swift` — replace the `body` property (lines 19–93) with:

```swift
    @State private var selectedTab = 0

    var body: some View {
        TabView(selection: $selectedTab) {
            profilesTab
                .tabItem {
                    Label(NSLocalizedString("Profiles", comment: ""), systemImage: "doc.text")
                }
                .tag(0)
            FileManageView()
                .tabItem {
                    Label(NSLocalizedString("Files", comment: ""), systemImage: "folder")
                }
                .tag(1)
        }
        .frame(minWidth: 700, minHeight: 440)
    }

    private var profilesTab: some View {
        NavigationSplitView {
            VStack(spacing: 0) {
                List(selection: $selectedProfileId) {
                    ForEach(repo.profiles) { profile in
                        HStack(spacing: 8) {
                            Image(systemName: "doc.text")
                                .foregroundColor(.secondary)
                                .font(.system(size: 13))
                            Text(profile.name)
                                .font(.system(size: 13))
                                .lineLimit(1)
                        }
                        .padding(.vertical, 3)
                        .tag(profile.id)
                        .contextMenu {
                            Button(NSLocalizedString("Rename...", comment: "")) {
                                renameTargetId = profile.id
                                newProfileName = profile.name
                                showRenameSheet = true
                            }
                            Divider()
                            Button(NSLocalizedString("Delete...", comment: ""), role: .destructive) {
                                repo.deleteProfile(profile.id)
                            }
                        }
                    }
                }
                .listStyle(.sidebar)

                Divider()

                Button(action: { showAddSheet = true }) {
                    Label(NSLocalizedString("Add Profile", comment: ""), systemImage: "plus")
                        .font(.system(size: 12))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.borderless)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            }
            .frame(minWidth: 200)
            .navigationSplitViewColumnWidth(min: 180, ideal: 220)
        } detail: {
            if let profileId = selectedProfileId {
                YamlEditorView(profileId: profileId)
                    .id(profileId)
            } else {
                VStack {
                    Image(systemName: "doc.text.magnifyingglass")
                        .font(.system(size: 28))
                        .foregroundColor(Color(nsColor: .tertiaryLabelColor))
                    Text(NSLocalizedString("Select a profile to edit", comment: ""))
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle(selectedProfileId.flatMap { id in
            repo.profiles.first { $0.id == id }?.name
        } ?? NSLocalizedString("Settings", comment: ""))
        .toolbar { ToolbarItem { Spacer() } }
        .onAppear {
            if selectedProfileId == nil, let first = repo.profiles.first {
                selectedProfileId = first.id
            }
        }
        .sheet(isPresented: $showAddSheet) { addProfileSheet }
        .sheet(isPresented: $showRenameSheet) { renameProfileSheet }
    }
```

Also remove these lines from `SettingsView` at the original positions:
- The `.frame(minWidth: 700, minHeight: 440)` (line 78) — moved to TabView level
- The `.navigationTitle(...)` (line 79–81) — moved to profilesTab
- The `.toolbar { ToolbarItem { Spacer() } }` (line 82) — moved to profilesTab
- The `.onAppear { ... }` (lines 83–86) — moved to profilesTab
- The `.sheet(...)` modifiers (lines 88–93) — moved to profilesTab

- [ ] **Step 2: Read the current `SettingsView.swift` to verify exact line content before editing**

Run: `cat -n macos/GostX/SettingsView.swift | head -100`

- [ ] **Step 3: Remove the `frame` modifier from the old body and keep the private view helpers**

The `addProfileSheet`, `renameProfileSheet`, and private vars remain unchanged — the old `body` computed property is replaced by the TabView-based version above.

- [ ] **Step 4: Verify the file compiles**

Open in Xcode: build the `GostX` target (`Cmd+B`).

- [ ] **Step 5: Commit**

```bash
git add macos/GostX/SettingsView.swift
git commit -m "feat: wrap SettingsView in TabView with Profiles and Files tabs

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: PacketTunnelProvider — Update Work Directory to `files/`

**Files:**
- Modify: `macos/GostXTunnel/PacketTunnelProvider.swift:101-110`

**Interfaces:**
- Consumes: `AppGroupConfig.containerURL` (existing)
- Produces: Updated `setWorkDirAndLog()` that creates `files/` directory and sets `LibgostSetWorkDir` to it

- [ ] **Step 1: Replace the `setWorkDirAndLog()` method**

Replace lines 101-110 in `PacketTunnelProvider.swift`:

```swift
    private func setWorkDirAndLog() {
        guard let containerURL = AppGroupConfig.containerURL else {
            os_log(.error, "[GostX] containerURL is nil!")
            return
        }
        let filesDir = containerURL.appendingPathComponent("files")
        try? FileManager.default.createDirectory(at: filesDir,
            withIntermediateDirectories: true, attributes: nil)
        LibgostSetWorkDir(filesDir.path, nil)
        let logFile = containerURL.appendingPathComponent("gost.log").path
        LibgostSetLogFile(logFile, nil)
        LibgostSetLogLevel("info")
    }
```

- [ ] **Step 2: Commit**

```bash
git add macos/GostXTunnel/PacketTunnelProvider.swift
git commit -m "fix: set work directory to files/ subdirectory for bypass file resolution

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: Xcode Project — Add New Files to Target

**Files:**
- Modify: `macos/GostX.xcodeproj/project.pbxproj`

**Interfaces:**
- Produces: `FileRepository.swift` and `FileManageView.swift` added to the GostX target

- [ ] **Step 1: Open the Xcode project and add the files manually**

1. Open `macos/GostX.xcodeproj` in Xcode
2. In the Project Navigator, select the `GostX` group (under the GostX project)
3. Right-click → **Add Files to "GostX"...**
4. Select `macos/GostX/FileRepository.swift` and `macos/GostX/FileManageView.swift`
5. Ensure **"Copy items if needed"** is unchecked (files are already in the correct location)
6. Ensure the target **GostX** is checked (NOT GostXTunnel)
7. Click **Add**

- [ ] **Step 2: Verify the files appear in the GostX target build phases**

In Xcode: Select the GostX project → GostX target → Build Phases → Compile Sources.
Verify `FileRepository.swift` and `FileManageView.swift` are listed.

- [ ] **Step 3: Build the project**

Run: `Cmd+B` in Xcode. Verify no compilation errors.

- [ ] **Step 4: Commit**

```bash
git add macos/GostX.xcodeproj/project.pbxproj
git commit -m "chore: add FileRepository and FileManageView to Xcode project

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Verification Checklist

After all tasks are complete, manually verify:

- [ ] Open the Settings window — both Profiles and Files tabs are visible
- [ ] Profiles tab works as before (profile list, YAML editor)
- [ ] Files tab shows empty state with "No files. Click + to import."
- [ ] Click import → NSOpenPanel appears → select a file → file appears in list
- [ ] Import a file with same name → overwrite sheet appears → Replace works, Cancel works
- [ ] Right-click/click "..." → Export → NSSavePanel appears → file saves correctly
- [ ] Right-click/click "..." → Rename → sheet appears → rename works
- [ ] Rename to invalid name → error alert
- [ ] Rename to existing name → error alert
- [ ] Right-click/click "..." → Copy Path → clipboard has correct path
- [ ] Right-click/click "..." → Delete → confirmation → file removed
- [ ] Start VPN with bypass config → `files/` directory exists in container
- [ ] Tunnel reads bypass files from `files/` correctly
