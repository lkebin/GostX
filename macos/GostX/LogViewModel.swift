// macos/GostX/LogViewModel.swift
import SwiftUI
import Combine

@MainActor
class LogViewModel: ObservableObject {
    @Published var lines: [String] = []
    @Published var isFollowing = false

    var scrollProxy: ScrollViewProxy?
    private var timer: Timer?
    private let logFileURL: URL?

    init(logFileURL: URL? = nil) {
        self.logFileURL = logFileURL ?? AppGroupConfig.containerURL?.appendingPathComponent("gost.log")
    }

    func onAppear(loggingEnabled: Bool) {
        loadLog()
        if loggingEnabled {
            startPolling()
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
        try? "".write(to: url, atomically: true, encoding: .utf8)
        lines = []
    }

    // MARK: - Private

    private func loadLog() {
        guard let url = logFileURL,
              FileManager.default.fileExists(atPath: url.path),
              let content = try? String(contentsOf: url, encoding: .utf8)
        else {
            lines = []
            return
        }
        lines = content.components(separatedBy: "\n").filter { !$0.isEmpty }
    }

    private func startPolling() {
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self else { return }
            DispatchQueue.main.async {
                self.loadLog()
            }
        }
    }

    private func stopPolling() {
        timer?.invalidate()
        timer = nil
    }
}
