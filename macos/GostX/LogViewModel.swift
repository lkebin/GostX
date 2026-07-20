// macos/GostX/LogViewModel.swift
import SwiftUI

// MARK: - Constants

private let chunkLines = 1000   // lines to load on initial display and per history chunk

@MainActor
class LogViewModel: ObservableObject {
    @Published var lines: [String] = []
    @Published var isFollowing = false

    private var timer: Timer?
    private let logFileURL: URL?
    private var totalLineCount = 0      // number of lines in the file (used to detect new lines)
    private var loadedFromEnd = 0       // how many lines from the end are loaded
    private var hasEarlierHistory = true
    private var isLoadingHistory = false
    private var readyForHistoryLoad = false

    init(logFileURL: URL? = nil) {
        self.logFileURL = logFileURL ?? AppGroupConfig.containerURL?.appendingPathComponent("gost.log")
    }

    func onAppear(loggingEnabled: Bool) {
        readyForHistoryLoad = false
        loadInitialTail()
        if loggingEnabled {
            startPolling()
        }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 500_000_000)
            readyForHistoryLoad = true
        }
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
            }
        }
    }

    private func startPolling() {
        guard let url = logFileURL else { return }
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self else { return }
            Task.detached(priority: .utility) { [weak self] in
                guard let self else { return }
                let allLines = readAllLines(from: url)
                let prevCount = await MainActor.run { self.totalLineCount }
                let prevLines = await MainActor.run { self.lines }
                guard allLines.count != prevCount || allLines.isEmpty != prevLines.isEmpty else { return }

                await MainActor.run { [weak self] in
                    guard let self else { return }
                    let newCount = allLines.count

                    if newCount < self.loadedFromEnd {
                        // File truncated or cleared
                        self.lines = Array(allLines.suffix(min(newCount, chunkLines)))
                        self.loadedFromEnd = min(newCount, chunkLines)
                        self.hasEarlierHistory = newCount > self.loadedFromEnd
                    } else if newCount > prevCount {
                        // New lines appended
                        let newLineCount = newCount - prevCount
                        let newLines = Array(allLines.suffix(newLineCount))
                        self.lines.append(contentsOf: newLines)
                        self.loadedFromEnd += newLineCount
                    } else if newCount < prevCount {
                        // File may have been partially rewritten (rotate or external write)
                        self.lines = Array(allLines.suffix(min(newCount, chunkLines)))
                        self.loadedFromEnd = min(newCount, chunkLines)
                        self.hasEarlierHistory = newCount > self.loadedFromEnd
                    }

                    self.totalLineCount = newCount
                }
            }
        }
    }

    private func stopPolling() {
        timer?.invalidate()
        timer = nil
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
