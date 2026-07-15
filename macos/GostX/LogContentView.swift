// macos/GostX/LogContentView.swift
import SwiftUI

@available(macOS 14.0, *)
struct LogContentView: View {
    @ObservedObject var vm: LogViewModel
    let loggingEnabled: Bool

    var body: some View {
        VStack(spacing: 0) {
            // Toolbar
            HStack(spacing: 8) {
                Button(action: { vm.isFollowing.toggle() }) {
                    Image(systemName: vm.isFollowing ? "pause.fill" : "play.fill")
                }
                .buttonStyle(.borderless)
                .help(vm.isFollowing
                    ? NSLocalizedString("Pause auto-scroll", comment: "")
                    : NSLocalizedString("Resume auto-scroll", comment: ""))

                Divider()
                    .frame(height: 16)

                Button(action: { vm.copyAll() }) {
                    Image(systemName: "doc.on.doc")
                }
                .buttonStyle(.borderless)
                .help(NSLocalizedString("Copy all", comment: ""))

                Button(action: { vm.clearLog() }) {
                    Image(systemName: "trash")
                }
                .buttonStyle(.borderless)
                .help(NSLocalizedString("Clear log", comment: ""))

                Spacer()

                Text("\(vm.lines.count) lines")
                    .font(.system(size: 10))
                    .foregroundColor(.secondary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .frame(height: 32)

            Divider()

            // Content
            if !loggingEnabled {
                VStack {
                    Spacer()
                    Image(systemName: "text.alignleft")
                        .font(.system(size: 28))
                        .foregroundColor(Color(nsColor: .tertiaryLabelColor))
                    Text(NSLocalizedString("Logging is off", comment: ""))
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                    Spacer()
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if vm.lines.isEmpty {
                VStack {
                    Spacer()
                    Image(systemName: "text.alignleft")
                        .font(.system(size: 28))
                        .foregroundColor(Color(nsColor: .tertiaryLabelColor))
                    Text(NSLocalizedString("No logs", comment: ""))
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                    Spacer()
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 1) {
                            ForEach(Array(vm.lines.enumerated()), id: \.offset) { _, line in
                                Text(line)
                                    .font(.system(size: 11, design: .monospaced))
                                    .textSelection(.enabled)
                            }
                        }
                        .padding(8)
                    }
                    .background(Color(nsColor: .textBackgroundColor))
                    .onAppear { vm.scrollProxy = proxy }
                }
            }
        }
        .onAppear { vm.onAppear() }
        .onDisappear { vm.onDisappear() }
        .onChange(of: vm.isFollowing) { _ in
            if vm.isFollowing, let proxy = vm.scrollProxy, !vm.lines.isEmpty {
                proxy.scrollTo(vm.lines.count - 1, anchor: .bottom)
            }
        }
    }
}
