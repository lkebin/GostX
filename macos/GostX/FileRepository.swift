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
