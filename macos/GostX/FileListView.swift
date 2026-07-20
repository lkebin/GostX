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
        if !vm.isAvailable {
            unavailableView
        } else {
            listContent
        }
    }

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

    private var listContent: some View {
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
                .listStyle(.automatic)
                .padding(.horizontal, 4)
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
