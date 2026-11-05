import SwiftUI

struct LogsView: View {
    @StateObject private var vm = LogViewModel()

    private var loggingEnabled: Bool { AppGroupConfig.loggingEnabled }

    var body: some View {
        Group {
            if !loggingEnabled {
                Text("Logging is disabled. Turn it on in Settings.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding()
            } else if vm.lines.isEmpty {
                Text("No logs")
                    .foregroundStyle(.secondary)
            } else {
                ScrollViewReader { proxy in
                    List(vm.lines.indices, id: \.self) { index in
                        Text(vm.lines[index])
                            .font(.system(.caption, design: .monospaced))
                            .listRowSeparator(.hidden)
                            .id(index)
                    }
                    .listStyle(.plain)
                    .onChange(of: vm.lines.count) { _, newCount in
                        guard vm.isFollowing, newCount > 0 else { return }
                        proxy.scrollTo(newCount - 1, anchor: .bottom)
                    }
                    .onChange(of: vm.isFollowing) { _, following in
                        guard following, !vm.lines.isEmpty else { return }
                        withAnimation {
                            proxy.scrollTo(vm.lines.count - 1, anchor: .bottom)
                        }
                    }
                }
            }
        }
        .navigationTitle("Logs")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    vm.isFollowing.toggle()
                } label: {
                    Image(systemName: vm.isFollowing ? "pause.fill" : "play.fill")
                }
                .accessibilityLabel(vm.isFollowing ? "Pause following" : "Follow logs")
                Button {
                    vm.loadMoreHistory()
                } label: {
                    Image(systemName: "arrow.up.to.line")
                }
                .accessibilityLabel("Load earlier logs")
                Button {
                    vm.copyAll()
                } label: {
                    Image(systemName: "doc.on.doc")
                }
                .accessibilityLabel("Copy logs")
                Button(role: .destructive) {
                    vm.clearLog()
                } label: {
                    Image(systemName: "trash")
                }
                .accessibilityLabel("Clear logs")
            }
        }
        .onAppear { vm.onAppear(loggingEnabled: loggingEnabled) }
        .onDisappear { vm.onDisappear() }
    }
}
