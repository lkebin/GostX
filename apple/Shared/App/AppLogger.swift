//
//  AppLogger.swift
//  GostX
//
//  Bridges main app os_log to gost.log for unified logging.
//  Uses POSIX C APIs to avoid NSFileHandle NSException crashes
//  when the Go tunnel extension concurrently writes to the same file.
//

import Foundation
import os

/// Writes log messages from the main app into the shared gost.log file
/// alongside the tunnel extension logs.
enum AppLogger {
    private static let queue = DispatchQueue(label: "cn.liukebin.gostx.applogger")
    private static var logFileURL: URL?
    /// Cap comes from App Group settings (default 2 MB); after rotation the
    /// file keeps the last half of it.
    private static var maxLogBytes: off_t { off_t(AppGroupConfig.logMaxSizeKB) * 1024 }
    private static var rotateKeepBytes: off_t { maxLogBytes / 2 }

    static func configure(containerURL: URL) {
        logFileURL = containerURL.appendingPathComponent("gost.log")
    }

    static func log(_ level: OSLogType = .default, _ message: String) {
        os_log(level, "%{public}@", message)
        guard AppGroupConfig.loggingEnabled else { return }
        writeToFile("[APP] \(message)")
    }

    // MARK: - Private

    private static func writeToFile(_ line: String) {
        guard let url = logFileURL else { return }
        let path = url.path
        let entry = "\(formatTimestamp()) \(line)\n"
        guard let data = entry.data(using: .utf8) else { return }

        queue.async {
            data.withUnsafeBytes { (buf: UnsafeRawBufferPointer) in
                guard let base = buf.baseAddress else { return }

                let fd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0o644)
                guard fd >= 0 else { return }
                let sizeBefore = lseek(fd, 0, SEEK_END)

                let written = write(fd, base, buf.count)
                close(fd)

                guard written == buf.count else { return }
                if sizeBefore + off_t(written) > maxLogBytes {
                    rotate(path: path, keepBytes: rotateKeepBytes)
                }
            }
        }
    }

    /// Truncates the file keeping only the last `rotateKeepBytes` bytes.
    /// Uses POSIX APIs — no Foundation FileHandle, no NSException risk.
    private static func rotate(path: String, keepBytes: off_t) {
        let rfd = open(path, O_RDONLY)
        guard rfd >= 0 else { return }
        let fileSize = lseek(rfd, 0, SEEK_END)
        guard fileSize > keepBytes else { close(rfd); return }

        let readSize = Int(keepBytes)
        lseek(rfd, fileSize - keepBytes, SEEK_SET)

        var buf = [UInt8](repeating: 0, count: readSize)
        let n = read(rfd, &buf, readSize)
        close(rfd)
        guard n > 0 else { return }

        // Write truncated content (O_TRUNC replaces the file atomically)
        let wfd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0o644)
        guard wfd >= 0 else { return }
        _ = write(wfd, buf, n)
        close(wfd)
    }

    private static func formatTimestamp() -> String {
        let df = DateFormatter()
        df.dateFormat = "HH:mm:ss.SSS"
        return df.string(from: Date())
    }
}
