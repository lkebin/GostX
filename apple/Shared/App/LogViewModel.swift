// macos/GostX/LogViewModel.swift
import SwiftUI

// MARK: - Constants

private let chunkLines = 1000   // lines to load on initial display and per history chunk
private let maxLines = 10000  // maximum in-memory lines; bounded for memory safety

@MainActor
class LogViewModel: ObservableObject {
    @Published var lines: [String] = []
    @Published var isFollowing = false

    private var fileMonitor: DispatchSourceFileSystemObject?
    private let logFileURL: URL?
    private var lastOffset: Int64 = 0
    private var totalLineCount = 0      // number of lines in the file (used to detect new lines)
    private var loadedFromEnd = 0       // how many lines from the end are loaded
    private var hasEarlierHistory = true
    private var isLoadingHistory = false
    private var readyForHistoryLoad = false

    init(logFileURL: URL? = nil) {
        self.logFileURL = logFileURL ?? AppGroupConfig.containerURL?.appendingPathComponent("gost.log")
    }

    func onAppear(loggingEnabled: Bool) {
        readyForHistoryLoad = true
        loadInitialTail()
        if loggingEnabled {
            startMonitoring()
        }
    }

    func onDisappear() {
        stopMonitoring()
    }

    func copyAll() {
        let text = lines.joined(separator: "\n")
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }

    func clearLog() {
        guard let url = logFileURL else { return }
        let fd = open(url.path, O_WRONLY | O_TRUNC)
        if fd >= 0 { close(fd) }
        lines = []
        totalLineCount = 0
        loadedFromEnd = 0
        hasEarlierHistory = false
    }

    /// Loads the previous chunk of history and prepends to `lines`.
    func loadMoreHistory() {
        guard let url = logFileURL, hasEarlierHistory, !isLoadingHistory, readyForHistoryLoad else { return }
        isLoadingHistory = true

        Task.detached(priority: .userInitiated) { [weak self] in
            guard let self else { return }
            let allLines = readAllLines(from: url)

            await MainActor.run { [weak self] in
                guard let self else { return }
                self.totalLineCount = allLines.count
                let total = allLines.count
                let currentCount = self.lines.count
                guard total > currentCount else {
                    self.hasEarlierHistory = false
                    self.isLoadingHistory = false
                    return
                }
                let remaining = total - currentCount
                let take = min(remaining, chunkLines)
                let startIdx = remaining - take
                self.lines.insert(contentsOf: allLines[startIdx..<(startIdx + take)], at: 0)
                self.loadedFromEnd += take
                self.hasEarlierHistory = self.loadedFromEnd < total
                self.isLoadingHistory = false
            }
        }
    }

    // MARK: - Private

    private func loadInitialTail() {
        guard let url = logFileURL else { return }
        Task.detached(priority: .userInitiated) { [weak self] in
            let allLines = readAllLines(from: url)
            let take = min(allLines.count, chunkLines)
            let tail = Array(allLines.suffix(take))
            await MainActor.run { [weak self] in
                guard let self else { return }
                self.lines = tail
                self.totalLineCount = allLines.count
                self.loadedFromEnd = take
                self.hasEarlierHistory = allLines.count > take
                if let url = self.logFileURL {
                    let fd = open(url.path, O_RDONLY)
                    if fd >= 0 {
                        var stat = stat()
                        if fstat(fd, &stat) == 0 {
                            self.lastOffset = stat.st_size
                        }
                        close(fd)
                    }
                }
            }
        }
    }

    private func readNewData(fd: Int32) -> Bool {
        var stat = stat()
        guard fstat(fd, &stat) == 0 else { return false }
        let size = stat.st_size

        if size < lastOffset {
            // File was truncated or cleared
            lastOffset = 0
            lseek(fd, 0, SEEK_SET)
            var buf = [UInt8](repeating: 0, count: Int(size))
            let n = read(fd, &buf, Int(size))
            if n > 0, let content = String(bytes: buf[0..<n], encoding: .utf8) {
                let allLines = content.components(separatedBy: "\n").filter { !$0.isEmpty }
                let take = min(allLines.count, chunkLines)
                DispatchQueue.main.async { [weak self] in
                    guard let self else { return }
                    self.lines = Array(allLines.suffix(take))
                    self.totalLineCount = allLines.count
                    self.loadedFromEnd = take
                    self.hasEarlierHistory = allLines.count > take
                }
            }
            lastOffset = size
            return false
        }

        if size == lastOffset { return false }

        lseek(fd, lastOffset, SEEK_SET)
        let bytesToRead = Int(size - lastOffset)
        var buf = [UInt8](repeating: 0, count: bytesToRead)
        let n = read(fd, &buf, bytesToRead)
        guard n > 0 else { lastOffset = size; return false }

        lastOffset = size

        guard let content = String(bytes: buf[0..<n], encoding: .utf8) else { return false }
        let newLines = content.components(separatedBy: "\n").filter { !$0.isEmpty }
        guard !newLines.isEmpty else { return false }

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.lines.append(contentsOf: newLines)
            self.totalLineCount += newLines.count
            self.loadedFromEnd += newLines.count
            self.trimToMax()
        }

        return true
    }

    private func startMonitoring() {
        guard let url = logFileURL else { return }
        let fd = open(url.path, O_RDONLY)
        guard fd >= 0 else { return }
        lastOffset = lseek(fd, 0, SEEK_END)

        let source = DispatchSource.makeFileSystemObjectSource(
            fileDescriptor: fd,
            eventMask: .write,
            queue: DispatchQueue.global(qos: .utility)
        )

        source.setEventHandler { [weak self] in
            guard let self else { return }
            var hasData = true
            while hasData {
                hasData = self.readNewData(fd: fd)
            }
        }

        source.setCancelHandler {
            close(fd)
        }

        source.resume()
        fileMonitor = source
    }

    private func stopMonitoring() {
        fileMonitor?.cancel()
        fileMonitor = nil
        lastOffset = 0
    }

    /// Trims oldest lines when over `maxLines` so memory stays bounded
    /// regardless of how the on-disk file is rotated.
    private func trimToMax() {
        let excess = lines.count - maxLines
        guard excess > 0 else { return }
        lines.removeFirst(excess)
        loadedFromEnd = max(0, loadedFromEnd - excess)
        hasEarlierHistory = loadedFromEnd < totalLineCount
    }
}

// MARK: - File Reader (POSIX, no Foundation I/O, no NSException risk)

/// Reads all lines from a file using POSIX open/read/close.
/// Returns empty array on any error. Never throws NSException.
private func readAllLines(from url: URL) -> [String] {
    let path = url.path
    let fd = open(path, O_RDONLY)
    guard fd >= 0 else { return [] }
    defer { close(fd) }

    let size = lseek(fd, 0, SEEK_END)
    guard size > 0 else { return [] }
    lseek(fd, 0, SEEK_SET)

    var buf = [UInt8](repeating: 0, count: Int(size))
    let n = read(fd, &buf, Int(size))
    guard n > 0 else { return [] }

    guard let content = String(bytes: buf[0..<n], encoding: .utf8) else { return [] }
    return content.components(separatedBy: "\n").filter { !$0.isEmpty }
}
