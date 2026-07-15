//
//  AppLogger.swift
//  GostX
//
//  Bridges main app os_log to gost.log for unified logging.
//

import Foundation
import os

/// Writes log messages from the main app into the shared gost.log file
/// alongside the tunnel extension logs.
enum AppLogger {
    private static let queue = DispatchQueue(label: "cn.liukebin.gostx.applogger")
    private static var logFileURL: URL?

    /// Must be called once on app startup with the container URL.
    static func configure(containerURL: URL) {
        logFileURL = containerURL.appendingPathComponent("gost.log")
    }

    static func log(_ level: OSLogType = .default, _ message: String) {
        os_log(level, "%{public}@", message)
        guard AppGroupConfig.loggingEnabled else { return }
        writeToFile("[APP] \(message)")
    }

    private static func writeToFile(_ line: String) {
        guard let url = logFileURL else { return }
        queue.async {
            let timestamp = ISO8601DateFormatter().string(from: Date())
            let entry = "\(formatTimestamp()) \(line)\n"
            if let data = entry.data(using: .utf8) {
                if let handle = try? FileHandle(forWritingTo: url) {
                    handle.seekToEndOfFile()
                    handle.write(data)
                    try? handle.close()
                } else {
                    try? data.write(to: url, options: .atomic)
                }
            }
        }
    }

    private static func formatTimestamp() -> String {
        let df = DateFormatter()
        df.dateFormat = "HH:mm:ss.SSS"
        return df.string(from: Date())
    }
}
