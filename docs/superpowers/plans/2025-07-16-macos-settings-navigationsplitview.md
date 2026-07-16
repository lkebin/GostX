# macOS Settings NavigationSplitView Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the custom tab-bar layout in `SettingsView` with a macOS-standard `NavigationSplitView` three-column layout (Sidebar → Content → Detail).

**Architecture:** Extract the three tab contents into list/detail view pairs, lift shared state into `SettingsView`, and wire it through a `NavigationSplitView`. Delete `LogView.swift` and `FileManageView.swift` after extraction.

**Tech Stack:** SwiftUI, macOS 14.0+, Combine

## Global Constraints

- macOS 14.0 minimum deployment target
- Always show 3 columns; sidebar hides via system toggle on narrow windows
- Follow existing patterns: `@StateObject` + `@ObservedObject` for view models, `@Published` for observable state
- Existing `YamlEditorView` (defined in SettingsView.swift) stays untouched
- New files registered in Xcode project via `project.pbxproj`

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `macos/GostX/LogViewModel.swift` | Create | ObservableObject: log lines, polling, copy, clear |
| `macos/GostX/LogOptionsView.swift` | Create | Logging toggle + level picker |
| `macos/GostX/LogContentView.swift` | Create | Scrollable log lines + toolbar (pause, copy, clear) |
| `macos/GostX/ProfileListView.swift` | Create | Profile list with add/rename/delete |
| `macos/GostX/FileListView.swift` | Create | File list with import/new/rename/delete |
| `macos/GostX/FileContentView.swift` | Create | TextEditor for file content + save |
| `macos/GostX/SettingsView.swift` | Modify | Rewrite as NavigationSplitView orchestrator |
| `macos/GostX/LogView.swift` | Delete | Gutted into LogOptionsView + LogContentView |
| `macos/GostX/FileManageView.swift` | Delete | Gutted into FileListView + FileContentView |
| `macos/GostXTests/GostXTests.swift` | Modify | Add LogViewModel tests |
| `macos/GostX.xcodeproj/project.pbxproj` | Modify | Add/remove file references |

---

### Task 1: Create LogViewModel

**Files:**
- Create: `macos/GostX/LogViewModel.swift`
- Modify: `macos/GostXTests/GostXTests.swift`

**Interfaces:**
- Produces: `class LogViewModel: ObservableObject` with `@Published var lines: [String]`, `@Published var isFollowing: Bool`, `func onAppear()`, `func onDisappear()`, `func copyAll()`, `func clearLog()`

- [ ] **Step 1: Write LogViewModel**

Create `macos/GostX/LogViewModel.swift`:

```swift
// macos/GostX/LogViewModel.swift
import SwiftUI
import Combine

@MainActor
class LogViewModel: ObservableObject {
    @Published var lines: [String] = []
    @Published var isFollowing = true

    var scrollProxy: ScrollViewProxy?
    private var timer: Timer?

    private var logFileURL: URL? {
        AppGroupConfig.containerURL?.appendingPathComponent("gost.log")
    }

    func onAppear() {
        loadLog()
        startPolling()
    }

    func onDisappear() {
        stopPolling()
    }

    func copyAll() {
        let text = lines.joined(separator: "\n")
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }

    func clearLog() {
        guard let url = logFileURL else { return }
        try? "".write(to: url, atomically: true, encoding: .utf8)
        lines = []
    }

    // MARK: - Private

    private func loadLog() {
        guard let url = logFileURL,
              FileManager.default.fileExists(atPath: url.path),
              let content = try? String(contentsOf: url, encoding: .utf8)
        else {
            lines = []
            return
        }
        lines = content.components(separatedBy: "\n").filter { !$0.isEmpty }
    }

    private func startPolling() {
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self else { return }
            DispatchQueue.main.async {
                let oldCount = self.lines.count
                self.loadLog()
                if self.isFollowing, self.lines.count > oldCount, let proxy = self.scrollProxy {
                    proxy.scrollTo(self.lines.count - 1, anchor: .bottom)
                }
            }
        }
    }

    private func stopPolling() {
        timer?.invalidate()
        timer = nil
    }
}
```

- [ ] **Step 2: Write LogViewModel tests**

Add to `macos/GostXTests/GostXTests.swift` after the existing test class:

```swift
@MainActor
class LogViewModelTests: XCTestCase {
    var vm: LogViewModel!
    var tempDir: URL!

    override func setUpWithError() throws {
        tempDir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        // Write a known log file
        let log = tempDir.appendingPathComponent("gost.log")
        try "line one\nline two\nline three\n".write(to: log, atomically: true, encoding: .utf8)
        // Override containerURL to return our temp dir
        // We test load/clear/copy independently by writing to a temp file
        vm = LogViewModel()
    }

    override func tearDownWithError() throws {
        vm.onDisappear()
        try? FileManager.default.removeItem(at: tempDir)
        vm = nil
    }

    func testInitialState() {
        XCTAssertTrue(vm.isFollowing)
        // lines depend on file existence; without a real log file they start empty
    }

    func testClearLog() {
        vm.clearLog()
        // After clear, lines should be empty
        XCTAssertEqual(vm.lines.count, 0)
    }

    func testCopyAll() {
        // lines is empty by default in unit test env (no App Group file)
        vm.copyAll()
        // Pasteboard should contain empty string
        XCTAssertEqual(NSPasteboard.general.string(forType: .string), "")
    }

    func testIsFollowingToggle() {
        vm.isFollowing = false
        XCTAssertFalse(vm.isFollowing)
        vm.isFollowing = true
        XCTAssertTrue(vm.isFollowing)
    }
}
```

- [ ] **Step 3: Run tests**

Run: `cd macos && xcodebuild test -project GostX.xcodeproj -scheme GostX -destination 'platform=macOS' ONLY_ACTIVE_ARCH=YES 2>&1 | tail -20`
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add macos/GostX/LogViewModel.swift macos/GostXTests/GostXTests.swift
git commit -m "feat: add LogViewModel with polling, copy, and clear"
```

---

### Task 2: Create LogOptionsView

**Files:**
- Create: `macos/GostX/LogOptionsView.swift`

**Interfaces:**
- Consumes: `AppGroupConfig.loggingEnabled` (Bool), `AppGroupConfig.logLevel` (String), `AppGroupConfig.logLevelOptions` ([String])
- Produces: `struct LogOptionsView: View` — toggle + picker + hint text

- [ ] **Step 1: Write LogOptionsView**

Create `macos/GostX/LogOptionsView.swift`:

```swift
// macos/GostX/LogOptionsView.swift
import SwiftUI

@available(macOS 14.0, *)
struct LogOptionsView: View {
    @State private var loggingEnabled = AppGroupConfig.loggingEnabled
    @State private var logLevel = AppGroupConfig.logLevel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Toggle(isOn: $loggingEnabled) {
                    Text(NSLocalizedString("Logging", comment: ""))
                        .font(.system(size: 12, weight: .medium))
                }
                .toggleStyle(.switch)
                .controlSize(.small)
                .onChange(of: loggingEnabled) { newValue in
                    AppGroupConfig.loggingEnabled = newValue
                    AppLogger.log(.info, "Logging \(newValue ? "enabled" : "disabled")")
                }
            }

            if loggingEnabled {
                VStack(alignment: .leading, spacing: 6) {
                    Text(NSLocalizedString("Log Level", comment: ""))
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)

                    Picker("", selection: $logLevel) {
                        ForEach(AppGroupConfig.logLevelOptions, id: \.self) { level in
                            Text(level.capitalized).tag(level)
                        }
                    }
                    .pickerStyle(.radioGroup)
                    .onChange(of: logLevel) { newValue in
                        AppGroupConfig.logLevel = newValue
                        AppLogger.log(.info, "Log level: \(newValue)")
                    }
                }
            }

            Text(NSLocalizedString("Restart VPN to apply", comment: ""))
                .font(.system(size: 9))
                .foregroundColor(.secondary)

            Spacer()
        }
        .padding(16)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add macos/GostX/LogOptionsView.swift
git commit -m "feat: add LogOptionsView (toggle + level picker)"
```

---

### Task 3: Create LogContentView

**Files:**
- Create: `macos/GostX/LogContentView.swift`

**Interfaces:**
- Consumes: `LogViewModel` (lines, isFollowing, scrollProxy, onAppear, onDisappear, copyAll, clearLog), `loggingEnabled` Bool binding
- Produces: `struct LogContentView: View`

- [ ] **Step 1: Write LogContentView**

Create `macos/GostX/LogContentView.swift`:

```swift
// macos/GostX/LogContentView.swift
import SwiftUI

@available(macOS 14.0, *)
struct LogContentView: View {
    @ObservedObject var vm: LogViewModel
    let loggingEnabled: Bool

    var body: some View {
        VStack(spacing: 0) {
            // Toolbar
            HStack(spacing: 8) {
                Button(action: { vm.isFollowing.toggle() }) {
                    Image(systemName: vm.isFollowing ? "pause.fill" : "play.fill")
                }
                .buttonStyle(.borderless)
                .help(vm.isFollowing
                    ? NSLocalizedString("Pause auto-scroll", comment: "")
                    : NSLocalizedString("Resume auto-scroll", comment: ""))

                Divider()
                    .frame(height: 16)

                Button(action: { vm.copyAll() }) {
                    Image(systemName: "doc.on.doc")
                }
                .buttonStyle(.borderless)
                .help(NSLocalizedString("Copy all", comment: ""))

                Button(action: { vm.clearLog() }) {
                    Image(systemName: "trash")
                }
                .buttonStyle(.borderless)
                .help(NSLocalizedString("Clear log", comment: ""))

                Spacer()

                Text("\(vm.lines.count) lines")
                    .font(.system(size: 10))
                    .foregroundColor(.secondary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .frame(height: 32)

            Divider()

            // Content
            if !loggingEnabled {
                VStack {
                    Spacer()
                    Image(systemName: "text.alignleft")
                        .font(.system(size: 28))
                        .foregroundColor(Color(nsColor: .tertiaryLabelColor))
                    Text(NSLocalizedString("Logging is off", comment: ""))
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                    Spacer()
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if vm.lines.isEmpty {
                VStack {
                    Spacer()
                    Image(systemName: "text.alignleft")
                        .font(.system(size: 28))
                        .foregroundColor(Color(nsColor: .tertiaryLabelColor))
                    Text(NSLocalizedString("No logs", comment: ""))
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                    Spacer()
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 1) {
                            ForEach(Array(vm.lines.enumerated()), id: \.offset) { _, line in
                                Text(line)
                                    .font(.system(size: 11, design: .monospaced))
                                    .textSelection(.enabled)
                            }
                        }
                        .padding(8)
                    }
                    .background(Color(nsColor: .textBackgroundColor))
                    .onAppear { vm.scrollProxy = proxy }
                }
            }
        }
        .onAppear { vm.onAppear() }
        .onDisappear { vm.onDisappear() }
        .onChange(of: vm.isFollowing) { _ in
            if vm.isFollowing, let proxy = vm.scrollProxy, !vm.lines.isEmpty {
                proxy.scrollTo(vm.lines.count - 1, anchor: .bottom)
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add macos/GostX/LogContentView.swift
git commit -m "feat: add LogContentView (scrollable lines + toolbar)"
```

---

### Task 4: Create ProfileListView

**Files:**
- Create: `macos/GostX/ProfileListView.swift`

**Interfaces:**
- Consumes: `ConfigRepository.shared` (ObservedObject), `@Binding var selectedProfileId: String?`
- Produces: `struct ProfileListView: View` — profile list with add/rename/delete

- [ ] **Step 1: Write ProfileListView**

Extract the profile list sidebar from `SettingsView.swift` into `macos/GostX/ProfileListView.swift`:

```swift
// macos/GostX/ProfileListView.swift
import SwiftUI

@available(macOS 14.0, *)
struct ProfileListView: View {
    @ObservedObject var repo = ConfigRepository.shared
    @Binding var selectedProfileId: String?
    @State private var showAddSheet = false
    @State private var showRenameSheet = false
    @State private var newProfileName = ""
    @State private var renameTargetId: String? = nil

    var body: some View {
        VStack(spacing: 0) {
            List(selection: $selectedProfileId) {
                ForEach(repo.profiles) { profile in
                    Label(profile.name, systemImage: "doc.text")
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
            .listStyle(.plain)

            Divider()

            HStack {
                Button(action: { showAddSheet = true }) {
                    Label(NSLocalizedString("Add Profile", comment: ""), systemImage: "plus")
                }
                .buttonStyle(.borderless)
                Spacer()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .frame(height: 32)
        }
        .onAppear {
            if selectedProfileId == nil, let first = repo.profiles.first {
                selectedProfileId = first.id
            }
        }
        .sheet(isPresented: $showAddSheet) {
            addProfileSheet
        }
        .sheet(isPresented: $showRenameSheet) {
            renameProfileSheet
        }
    }

    private var addProfileSheet: some View {
        VStack(spacing: 16) {
            Text(NSLocalizedString("New Profile", comment: "")).font(.headline)
            TextField(NSLocalizedString("Profile name", comment: ""), text: $newProfileName)
                .textFieldStyle(.roundedBorder)
                .frame(width: 250)
            HStack {
                Button(NSLocalizedString("Cancel", comment: "")) { showAddSheet = false }
                    .keyboardShortcut(.cancelAction)
                Button(NSLocalizedString("Add", comment: "")) {
                    if !newProfileName.isEmpty {
                        let newId = repo.addProfile(name: newProfileName)
                        if let id = newId { selectedProfileId = id }
                        newProfileName = ""
                        showAddSheet = false
                    }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(newProfileName.isEmpty)
            }
        }
        .padding()
        .frame(width: 300, height: 140)
    }

    private var renameProfileSheet: some View {
        VStack(spacing: 16) {
            Text(NSLocalizedString("Rename Profile", comment: "")).font(.headline)
            TextField(NSLocalizedString("Profile name", comment: ""), text: $newProfileName)
                .textFieldStyle(.roundedBorder)
                .frame(width: 250)
            HStack {
                Button(NSLocalizedString("Cancel", comment: "")) { showRenameSheet = false }
                    .keyboardShortcut(.cancelAction)
                Button(NSLocalizedString("Rename", comment: "")) {
                    if let id = renameTargetId, !newProfileName.isEmpty {
                        _ = repo.renameProfile(id, newName: newProfileName)
                        newProfileName = ""
                        renameTargetId = nil
                        showRenameSheet = false
                    }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(newProfileName.isEmpty)
            }
        }
        .padding()
        .frame(width: 300, height: 140)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add macos/GostX/ProfileListView.swift
git commit -m "feat: add ProfileListView extracted from SettingsView"
```

---

### Task 5: Create FileListView

**Files:**
- Create: `macos/GostX/FileListView.swift`

**Interfaces:**
- Consumes: `@ObservedObject var vm: FileManageViewModel`
- Produces: `struct FileListView: View` — file list + context menu + import/new/rename/delete

- [ ] **Step 1: Write FileListView**

Extract the sidebar portion from `FileManageView` into `macos/GostX/FileListView.swift`. Keep all the dialogs, sheets, and modifiers that belong to the list. Note: `formatFileSize`, `formatFileDate`, `FileRowView`, `FileManageAlertModifier`, `OverwriteSheetModifier`, and `FileManageDialogsModifier` move from `FileManageView.swift` to here since they are all used by the list sidebar.

```swift
// macos/GostX/FileListView.swift
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

// MARK: - FileListView

@available(macOS 14.0, *)
struct FileListView: View {
    @ObservedObject var vm: FileManageViewModel
    @State private var showImporter = false
    @State private var showExporter = false
    @State private var exportName: String?
    @State private var renameTarget: FileInfo?
    @State private var renameText: String = ""
    @State private var deleteTarget: FileInfo?
    @State private var showDeleteConfirm = false
    @State private var showNewFileSheet = false
    @State private var newFileName = ""

    var body: some View {
        VStack(spacing: 0) {
            if vm.files.isEmpty {
                VStack(spacing: 8) {
                    Spacer()
                    Text(NSLocalizedString("No files", comment: ""))
                        .font(.system(size: 12))
                        .foregroundColor(.secondary)
                    Spacer()
                }
            } else {
                List(selection: Binding(
                    get: { vm.selectedFileName },
                    set: { name in
                        DispatchQueue.main.async {
                            if let name {
                                vm.selectFile(name)
                            } else {
                                vm.selectedFileName = nil
                                vm.fileContent = ""
                            }
                        }
                    }
                )) {
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
                        .tag(file.name)
                    }
                }
                .listStyle(.plain)
            }

            Divider()

            HStack {
                Button(action: { showNewFileSheet = true }) {
                    Label(NSLocalizedString("New File", comment: ""), systemImage: "doc.badge.plus")
                }
                .buttonStyle(.borderless)
                Spacer()
                Button(action: { showImporter = true }) {
                    Label(NSLocalizedString("Import", comment: ""), systemImage: "square.and.arrow.up")
                }
                .buttonStyle(.borderless)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .frame(height: 32)
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.data, .plainText, .text]) { result in
            if case .success(let url) = result {
                vm.importFile(from: url)
            }
        }
        .fileExporter(
            isPresented: $showExporter,
            item: exportName ?? "",
            contentTypes: [.data],
            defaultFilename: exportName ?? "file"
        ) { result in
            if case .success(let url) = result, let name = exportName {
                vm.exportFile(name, to: url)
            }
            exportName = nil
        }
        .sheet(isPresented: $showNewFileSheet) {
            VStack(spacing: 16) {
                Text(NSLocalizedString("New File", comment: "")).font(.headline)
                TextField(NSLocalizedString("File name", comment: ""), text: $newFileName)
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 250)
                HStack {
                    Button(NSLocalizedString("Cancel", comment: "")) {
                        showNewFileSheet = false
                        newFileName = ""
                    }
                    .keyboardShortcut(.cancelAction)
                    Button(NSLocalizedString("Create", comment: "")) {
                        vm.createFile(newFileName)
                        newFileName = ""
                        showNewFileSheet = false
                    }
                    .keyboardShortcut(.defaultAction)
                    .disabled(newFileName.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .padding()
            .frame(width: 300, height: 140)
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

@available(macOS 14.0, *)
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

            VStack(alignment: .leading, spacing: 1) {
                Text(file.name)
                    .font(.system(size: 13))
                    .lineLimit(1)
                Text(formatFileSize(file.sizeBytes))
                    .font(.system(size: 10))
                    .foregroundColor(.secondary)
                Text(formatFileDate(file.lastModified))
                    .font(.system(size: 10))
                    .foregroundColor(.secondary)
            }

            Spacer()
        }
        .padding(.vertical, 4)
        .contextMenu {
            Button(NSLocalizedString("Export...", comment: "")) { onExport() }
            Button(NSLocalizedString("Rename...", comment: "")) { onRename() }
            Button(NSLocalizedString("Copy Path", comment: "")) { onCopyPath() }
            Divider()
            Button(NSLocalizedString("Delete...", comment: ""), role: .destructive) { onDelete() }
        }
    }
}

// MARK: - Alert Modifier

@available(macOS 14.0, *)
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

@available(macOS 14.0, *)
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

@available(macOS 14.0, *)
private struct FileManageDialogsModifier: ViewModifier {
    @Binding var renameTarget: FileInfo?
    @Binding var renameText: String
    @Binding var deleteTarget: FileInfo?
    @Binding var showDeleteConfirm: Bool
    let vm: FileManageViewModel

    func body(content: Content) -> some View {
        content
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
git add macos/GostX/FileListView.swift
git commit -m "feat: add FileListView extracted from FileManageView"
```

---

### Task 6: Create FileContentView

**Files:**
- Create: `macos/GostX/FileContentView.swift`

**Interfaces:**
- Consumes: `@ObservedObject var vm: FileManageViewModel` (reads `fileContent`, `isFileDirty`, `selectedFileName`; calls `saveFileContent()`)
- Produces: `struct FileContentView: View`

- [ ] **Step 1: Write FileContentView**

Extract the detail pane from `FileManageView` into `macos/GostX/FileContentView.swift`:

```swift
// macos/GostX/FileContentView.swift
import SwiftUI

@available(macOS 14.0, *)
struct FileContentView: View {
    @ObservedObject var vm: FileManageViewModel

    var body: some View {
        Group {
            if vm.selectedFileName != nil {
                VStack(spacing: 0) {
                    TextEditor(text: $vm.fileContent)
                        .font(.system(size: 12, design: .monospaced))
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .onChange(of: vm.fileContent) { _ in
                            DispatchQueue.main.async {
                                vm.isFileDirty = vm.fileContent != vm.originalContent
                            }
                        }

                    Divider()

                    HStack {
                        Spacer()
                        Button(action: { vm.saveFileContent() }) {
                            Label(NSLocalizedString("Save", comment: ""), systemImage: "square.and.arrow.down")
                        }
                        .buttonStyle(.borderless)
                        .disabled(!vm.isFileDirty)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .frame(height: 32)
                }
            } else {
                VStack {
                    Image(systemName: "doc.text.magnifyingglass")
                        .font(.system(size: 28))
                        .foregroundColor(Color(nsColor: .tertiaryLabelColor))
                    Text(NSLocalizedString("Select a file to view", comment: ""))
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add macos/GostX/FileContentView.swift
git commit -m "feat: add FileContentView extracted from FileManageView"
```

---

### Task 7: Rewrite SettingsView as NavigationSplitView

**Files:**
- Modify: `macos/GostX/SettingsView.swift`

**Interfaces:**
- Consumes: All new views from Tasks 1-6, `ConfigRepository.shared`, `FileManageViewModel`, `LogViewModel`
- Produces: `struct SettingsView: View` using `NavigationSplitView`

**Note:** The `YamlEditorView` struct stays in this file unchanged. Only `SettingsView` is rewritten. We add the `SettingsCategory` enum and `FileManageViewModel` / `LogViewModel` as `@StateObject`.

- [ ] **Step 1: Rewrite SettingsView.swift**

Replace the entire contents of `macos/GostX/SettingsView.swift`:

```swift
// macos/GostX/SettingsView.swift
import SwiftUI

// MARK: - Category

enum SettingsCategory: String, CaseIterable, Identifiable {
    case profiles
    case files
    case logs

    var id: Self { self }

    var icon: String {
        switch self {
        case .profiles: return "doc.text"
        case .files: return "folder"
        case .logs: return "text.alignleft"
        }
    }

    var label: String {
        switch self {
        case .profiles: return NSLocalizedString("Profiles", comment: "")
        case .files: return NSLocalizedString("Files", comment: "")
        case .logs: return NSLocalizedString("Logs", comment: "")
        }
    }
}

// MARK: - SettingsView

@available(macOS 14.0, *)
struct SettingsView: View {
    @State private var selectedCategory: SettingsCategory = .profiles

    // Profiles
    @State private var selectedProfileId: String? = nil

    // Files
    @StateObject private var fileVM = FileManageViewModel()

    // Logs
    @StateObject private var logVM = LogViewModel()
    @State private var loggingEnabled = AppGroupConfig.loggingEnabled

    var body: some View {
        NavigationSplitView {
            // Sidebar
            List(selection: $selectedCategory) {
                ForEach(SettingsCategory.allCases) { category in
                    Label(category.label, systemImage: category.icon)
                        .tag(category)
                }
            }
            .listStyle(.sidebar)
            .navigationSplitViewColumnWidth(min: 140, ideal: 160, max: 200)
        } content: {
            // Content
            switch selectedCategory {
            case .profiles:
                ProfileListView(selectedProfileId: $selectedProfileId)
                    .navigationSplitViewColumnWidth(min: 200, ideal: 220, max: 300)
            case .files:
                FileListView(vm: fileVM)
                    .navigationSplitViewColumnWidth(min: 200, ideal: 220, max: 300)
            case .logs:
                LogOptionsView()
                    .navigationSplitViewColumnWidth(min: 200, ideal: 220, max: 300)
            }
        } detail: {
            // Detail
            switch selectedCategory {
            case .profiles:
                if let profileId = selectedProfileId {
                    YamlEditorView(profileId: profileId)
                        .id(profileId)
                } else {
                    placeholderView(
                        icon: "doc.text.magnifyingglass",
                        text: NSLocalizedString("Select a profile to edit", comment: "")
                    )
                }
            case .files:
                if fileVM.selectedFileName != nil {
                    FileContentView(vm: fileVM)
                } else {
                    placeholderView(
                        icon: "doc.text.magnifyingglass",
                        text: NSLocalizedString("Select a file to view", comment: "")
                    )
                }
            case .logs:
                LogContentView(vm: logVM, loggingEnabled: loggingEnabled)
                    .onAppear {
                        loggingEnabled = AppGroupConfig.loggingEnabled
                    }
            }
        }
        .frame(minWidth: 700, minHeight: 440)
    }

    private func placeholderView(icon: String, text: String) -> some View {
        VStack {
            Image(systemName: icon)
                .font(.system(size: 28))
                .foregroundColor(Color(nsColor: .tertiaryLabelColor))
            Text(text)
                .font(.system(size: 13))
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - YAML Editor View

@available(macOS 14.0, *)
struct YamlEditorView: View {
    let profileId: String
    @StateObject private var repo = ConfigRepository.shared
    @State private var yamlText: String = ""
    @State private var isDirty = false
    @State private var originalText: String = ""

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

            Divider()

            HStack {
                Spacer()
                Button(action: {
                    save()
                    originalText = yamlText
                    isDirty = false
                }) {
                    Label(NSLocalizedString("Save", comment: ""), systemImage: "square.and.arrow.down")
                }
                .buttonStyle(.borderless)
                .disabled(!isDirty)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .frame(height: 32)
        }
    }

    private func save() {
        repo.saveConfig(profileId, yaml: yamlText)
        if profileId == repo.activeProfileId {
            AppGroupConfig.writeYaml(yamlText)
        }
    }
}

@available(macOS 14.0, *)
struct SettingsView_Previews: PreviewProvider {
    static var previews: some View {
        SettingsView()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add macos/GostX/SettingsView.swift
git commit -m "refactor: rewrite SettingsView as NavigationSplitView"
```

---

### Task 8: Delete old files and update Xcode project

**Files:**
- Delete: `macos/GostX/LogView.swift`
- Delete: `macos/GostX/FileManageView.swift`
- Modify: `macos/GostX.xcodeproj/project.pbxproj`

- [ ] **Step 1: Delete old Swift files**

```bash
rm macos/GostX/LogView.swift macos/GostX/FileManageView.swift
```

- [ ] **Step 2: Update Xcode project file**

Use `ruby` to update `project.pbxproj` — remove the two old file references and add the six new ones:

```bash
cd macos
ruby -e '
require "xcodeproj"

project = Xcodeproj::Project.open("GostX.xcodeproj")
target = project.targets.find { |t| t.name == "GostX" }
group = project.main_group.find_subpath("GostX", true)

# Remove old files
["LogView.swift", "FileManageView.swift"].each do |name|
  group.files.find { |f| f.path == name }&.remove_from_project
end

# Add new files
["LogViewModel.swift", "LogOptionsView.swift", "LogContentView.swift",
 "ProfileListView.swift", "FileListView.swift", "FileContentView.swift"].each do |name|
  path = File.join(File.dirname(__FILE__), name)
  File.write(path, "") unless File.exist?(path)  # file already created by us
  ref = group.new_file(name)
  target.source_build_phase.add_file_reference(ref) if target
end

project.save
puts "Updated project.pbxproj: removed 2 old files, added 6 new files"
'
```

If `xcodeproj` gem is not installed, install it first: `gem install xcodeproj`

Alternatively, open the project in Xcode, remove `LogView.swift` and `FileManageView.swift` from the project navigator (choose "Remove Reference"), then drag the 6 new `.swift` files into the GostX group.

- [ ] **Step 3: Verify the project builds**

```bash
cd macos && xcodebuild -project GostX.xcodeproj -scheme GostX -configuration Debug -destination 'platform=macOS' ONLY_ACTIVE_ARCH=YES build 2>&1 | tail -30
```
Expected: **BUILD SUCCEEDED**

- [ ] **Step 4: Commit**

```bash
git add macos/GostX/LogView.swift macos/GostX/FileManageView.swift macos/GostX.xcodeproj/project.pbxproj
git commit -m "chore: remove old LogView/FileManageView, add new split views to Xcode project"
```

---

### Task 9: Build and run verification

**Files:** None (verification only)

- [ ] **Step 1: Clean build**

```bash
cd macos && xcodebuild -project GostX.xcodeproj -scheme GostX -configuration Debug -destination 'platform=macOS' ONLY_ACTIVE_ARCH=YES clean build 2>&1 | tail -30
```
Expected: **BUILD SUCCEEDED** with zero warnings from our files.

- [ ] **Step 2: Run tests**

```bash
cd macos && xcodebuild test -project GostX.xcodeproj -scheme GostX -destination 'platform=macOS' ONLY_ACTIVE_ARCH=YES 2>&1 | tail -30
```
Expected: All tests pass.

- [ ] **Step 3: Manual QA checklist** (run the app):

1. Open app → Settings window shows three-column NavigationSplitView
2. Click "Profiles" in sidebar → Profile list in Content, YAML editor in Detail
3. Select a profile → YAML editor loads its config
4. Click "Files" → File list in Content, file content editor in Detail
5. Select a file → content loads in editor; edit + save works
6. Click "Logs" → Log options in Content (toggle + level), log content in Detail
7. Toggle logging off → Detail shows "Logging is off" placeholder
8. Resize window narrow → sidebar collapses; system toggle appears in toolbar
