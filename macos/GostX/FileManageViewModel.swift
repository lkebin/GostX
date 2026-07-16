// macos/GostX/FileManageViewModel.swift
import SwiftUI
import AppKit

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
