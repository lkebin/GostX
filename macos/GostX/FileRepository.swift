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
        guard let trimmed = try? validateName(name) else { return false }
        let url = workDir.appendingPathComponent(trimmed)
        var isDir: ObjCBool = false
        return FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir)
            && !isDir.boolValue
    }

    // MARK: - Import

    @discardableResult
    func importFile(from sourceURL: URL) throws -> FileInfo {
        let name = sourceURL.lastPathComponent
        let trimmed = try validateName(name)

        // Reject directory sources — we only copy regular files.
        var isDir: ObjCBool = false
        FileManager.default.fileExists(atPath: sourceURL.path, isDirectory: &isDir)
        guard !isDir.boolValue else {
            throw FileRepositoryError.notAFile(name)
        }

        let target = workDir.appendingPathComponent(trimmed)
        // Remove existing file before copy
        if FileManager.default.fileExists(atPath: target.path) {
            try FileManager.default.removeItem(at: target)
        }
        try FileManager.default.copyItem(at: sourceURL, to: target)
        let attrs = try target.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
        return FileInfo(
            name: trimmed,
            sizeBytes: Int64(attrs.fileSize ?? 0),
            lastModified: attrs.contentModificationDate ?? Date()
        )
    }

    // MARK: - Export

    func exportFile(_ name: String, to destURL: URL) throws {
        let trimmed = try validateName(name)
        let source = workDir.appendingPathComponent(trimmed)
        guard FileManager.default.fileExists(atPath: source.path) else {
            throw FileRepositoryError.fileNotFound(trimmed)
        }
        if FileManager.default.fileExists(atPath: destURL.path) {
            try FileManager.default.removeItem(at: destURL)
        }
        try FileManager.default.copyItem(at: source, to: destURL)
    }

    // MARK: - Rename

    func renameFile(_ oldName: String, to newName: String) throws {
        let trimmedOld = try validateName(oldName)
        let trimmedNew = try validateName(newName)
        let oldURL = workDir.appendingPathComponent(trimmedOld)
        let newURL = workDir.appendingPathComponent(trimmedNew)
        guard FileManager.default.fileExists(atPath: oldURL.path) else {
            throw FileRepositoryError.fileNotFound(trimmedOld)
        }
        if FileManager.default.fileExists(atPath: newURL.path) {
            throw FileRepositoryError.fileAlreadyExists(trimmedNew)
        }
        try FileManager.default.moveItem(at: oldURL, to: newURL)
    }

    // MARK: - Delete

    func deleteFile(_ name: String) throws {
        let trimmed = try validateName(name)
        let url = workDir.appendingPathComponent(trimmed)
        if !FileManager.default.fileExists(atPath: url.path) { return }
        var isDir: ObjCBool = false
        FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir)
        guard !isDir.boolValue else {
            throw FileRepositoryError.notAFile(trimmed)
        }
        try FileManager.default.removeItem(at: url)
    }

    // MARK: - Path

    func filePath(_ name: String) -> String {
        let safe = (try? validateName(name)) ?? name
        return workDir.appendingPathComponent(safe).path
    }

    // MARK: - Validation

    private func validateName(_ name: String) throws -> String {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty || trimmed.contains("..") || trimmed.contains("/") {
            throw FileRepositoryError.invalidName
        }
        return trimmed
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
