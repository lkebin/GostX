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
