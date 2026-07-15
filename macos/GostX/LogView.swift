//
//  LogView.swift
//  GostX
//
//  Displays gost.log content with auto-scroll, copy, and clear.
//

import SwiftUI

@available(macOS 14.0, *)
struct LogView: View {
    @State private var lines: [String] = []
    @State private var isFollowing = true
    @State private var timer: Timer?
    @State private var scrollProxy: ScrollViewProxy?
    @State private var loggingEnabled = AppGroupConfig.loggingEnabled
    @State private var logLevel = AppGroupConfig.logLevel

    private var logFileURL: URL? {
        AppGroupConfig.containerURL?.appendingPathComponent("gost.log")
    }

    var body: some View {
        VStack(spacing: 0) {
            // Toolbar — row 1: log controls
            HStack(spacing: 8) {
                Toggle(isOn: $loggingEnabled) {
                    Text(NSLocalizedString("Logging", comment: "")).font(.system(size: 11))
                }
                .toggleStyle(.switch)
                .controlSize(.mini)
                .onChange(of: loggingEnabled) { newValue in
                    AppGroupConfig.loggingEnabled = newValue
                    AppLogger.log(.info, "Logging \(newValue ? "enabled" : "disabled")")
                }

                if loggingEnabled {
                    Picker("", selection: $logLevel) {
                        ForEach(AppGroupConfig.logLevelOptions, id: \.self) { level in
                            Text(level.capitalized).tag(level)
                        }
                    }
                    .pickerStyle(.menu)
                    .fixedSize()
                    .onChange(of: logLevel) { newValue in
                        AppGroupConfig.logLevel = newValue
                        AppLogger.log(.info, "Log level: \(newValue)")
                    }
                }

                Spacer()

                Text(NSLocalizedString("Restart VPN to apply", comment: ""))
                    .font(.system(size: 9))
                    .foregroundColor(.secondary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 4)

            Divider()

            // Toolbar — row 2: view controls
            HStack(spacing: 8) {
                Button(action: { isFollowing.toggle() }) {
                    Image(systemName: isFollowing ? "pause.fill" : "play.fill")
                }
                .buttonStyle(.borderless)
                .help(isFollowing
                    ? NSLocalizedString("Pause auto-scroll", comment: "")
                    : NSLocalizedString("Resume auto-scroll", comment: ""))

                Divider()
                    .frame(height: 16)

                Button(action: copyAll) {
                    Image(systemName: "doc.on.doc")
                }
                .buttonStyle(.borderless)
                .help(NSLocalizedString("Copy all", comment: ""))

                Button(action: clearLog) {
                    Image(systemName: "trash")
                }
                .buttonStyle(.borderless)
                .help(NSLocalizedString("Clear log", comment: ""))

                Spacer()

                Text("\(lines.count) lines")
                    .font(.system(size: 10))
                    .foregroundColor(.secondary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .frame(height: 32)

            Divider()

            // Log content
            if lines.isEmpty {
                VStack {
                    Image(systemName: "text.alignleft")
                        .font(.system(size: 28))
                        .foregroundColor(Color(nsColor: .tertiaryLabelColor))
                    Text(NSLocalizedString("No logs", comment: ""))
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 1) {
                            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                                Text(line)
                                    .font(.system(size: 11, design: .monospaced))
                                    .textSelection(.enabled)
                            }
                        }
                        .padding(8)
                    }
                    .background(Color(nsColor: .textBackgroundColor))
                    .onAppear { scrollProxy = proxy }
                }
            }
        }
        .onAppear {
            loadLog()
            startPolling()
        }
        .onDisappear {
            stopPolling()
        }
        .onChange(of: isFollowing) { _ in
            if isFollowing, let proxy = scrollProxy, !lines.isEmpty {
                proxy.scrollTo(lines.count - 1, anchor: .bottom)
            }
        }
    }

    // MARK: - Actions

    private func loadLog() {
        guard let url = logFileURL, FileManager.default.fileExists(atPath: url.path) else {
            lines = []
            return
        }
        do {
            let content = try String(contentsOf: url, encoding: .utf8)
            let newLines = content.components(separatedBy: "\n").filter { !$0.isEmpty }
            lines = newLines
        } catch {
            lines = []
        }
    }

    private func startPolling() {
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { _ in
            DispatchQueue.main.async {
                let oldCount = lines.count
                loadLog()
                if isFollowing, lines.count > oldCount, let proxy = scrollProxy {
                    proxy.scrollTo(lines.count - 1, anchor: .bottom)
                }
            }
        }
    }

    private func stopPolling() {
        timer?.invalidate()
        timer = nil
    }

    private func copyAll() {
        let text = lines.joined(separator: "\n")
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }

    private func clearLog() {
        guard let url = logFileURL else { return }
        try? "".write(to: url, atomically: true, encoding: .utf8)
        lines = []
    }
}
