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
    @Published var selectedFileName: String?
    @Published var fileContent: String = ""
    @Published var isFileDirty: Bool = false
    var originalContent: String = ""

    private let repo: FileRepository?

    init() {
        repo = FileRepository()
        refresh()
    }

    var isAvailable: Bool { repo != nil }

    func refresh() {
        files = repo?.listFiles() ?? []
    }

    func selectFile(_ name: String) {
        selectedFileName = name
        let content = repo?.readFileContent(name) ?? ""
        originalContent = content
        fileContent = content
        isFileDirty = false
    }

    func saveFileContent() {
        guard let repo, let name = selectedFileName else { return }
        do {
            try repo.writeFileContent(name, content: fileContent)
            originalContent = fileContent
            isFileDirty = false
            refresh()
        } catch {
            alertMessage = error.localizedDescription
        }
    }

    func createFile(_ name: String) {
        guard let repo else { return }
        do {
            try repo.createFile(name)
            refresh()
        } catch {
            alertMessage = error.localizedDescription
        }
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
            if selectedFileName == oldName {
                selectedFileName = newName
                fileContent = repo.readFileContent(newName) ?? ""
            }
            refresh()
        } catch {
            alertMessage = error.localizedDescription
        }
    }

    func deleteFile(_ name: String) {
        guard let repo else { return }
        do {
            try repo.deleteFile(name)
            if selectedFileName == name {
                selectedFileName = nil
                fileContent = ""
            }
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

// MARK: - FileManageView

@available(macOS 14.0, *)
struct FileManageView: View {
    @StateObject private var vm = FileManageViewModel()
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
        HStack(spacing: 0) {
            if !vm.isAvailable {
                unavailableView
            } else {
                fileSidebar

                Divider()

                fileDetail
            }
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.data, .plainText, .text]) { result in
            if case .success(let url) = result {
                vm.importFile(from: url)
            }
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
    }

    // MARK: - Sidebar

    private var fileSidebar: some View {
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
                            vm.selectedFileName = name
                            if let name { vm.selectFile(name) }
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
        .frame(width: 200)
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

    // MARK: - Detail

    private var fileDetail: some View {
        Group {
            if let _ = vm.selectedFileName {
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

    // MARK: - Unavailable

    private var unavailableView: some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 32))
                .foregroundColor(.secondary)
            Text(NSLocalizedString("App Group container not available.", comment: ""))
                .font(.system(size: 13))
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
