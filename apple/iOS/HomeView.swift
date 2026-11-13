import SwiftUI
import NetworkExtension

struct HomeView: View {
    @ObservedObject private var repo = ConfigRepository.shared
    @ObservedObject private var vpn = VpnManager.shared

    @State private var path: [Route] = []
    @State private var showAddSheet = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if repo.profiles.isEmpty {
                    Text("No profiles yet. Tap + to create one.")
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                        .padding()
                } else {
                    List {
                        ForEach(repo.profiles) { profile in
                            profileRow(profile)
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("GostX")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if AppGroupConfig.loggingEnabled {
                        Button { path.append(.logs) } label: {
                            Image(systemName: "doc.text")
                        }
                    }
                    Button { showAddSheet = true } label: {
                        Image(systemName: "plus")
                    }
                    Button { path.append(.settings) } label: {
                        Image(systemName: "gearshape")
                    }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if !repo.profiles.isEmpty {
                    ConnectButton(status: vpn.status) { toggleVPN() }
                        .padding(.bottom, 12)
                }
            }
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .configEdit(let id): ConfigEditView(profileId: id)
                case .logs: LogsView()
                case .settings: SettingsView()
                case .fileManage: FileManageView()
                }
            }
        }
        .sheet(isPresented: $showAddSheet) {
            AddProfileSheet(existingNames: Set(repo.profiles.map(\.name))) { name in
                if let id = repo.addProfile(name: name) {
                    showAddSheet = false
                    path.append(.configEdit(profileId: id))
                }
            }
        }
        .alert("Error", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) {
            Button("OK") { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
        .onAppear { surfaceVPNSetupError() }
        .onChange(of: vpn.lastError) { _, _ in surfaceVPNSetupError() }
        .onChange(of: vpn.status) { _, newValue in
            if newValue == .disconnected || newValue == .invalid {
                consumeTunnelError()
            }
        }
        .onChange(of: vpn.lastStopReason) { _, newValue in
            guard let newValue else { return }
            vpn.lastStopReason = nil
            errorMessage = newValue
        }
    }

    private func profileRow(_ profile: Profile) -> some View {
        HStack(spacing: 12) {
            Button {
                repo.setActiveProfile(profile.id)
            } label: {
                Image(systemName: profile.id == repo.activeProfileId
                      ? "largecircle.fill.circle" : "circle")
                    .font(.title3)
            }
            .buttonStyle(.plain)
            .disabled(vpn.status != .disconnected && vpn.status != .invalid)

            Text(profile.name)

            Spacer()

            Button {
                path.append(.configEdit(profileId: profile.id))
            } label: {
                Image(systemName: "chevron.right").foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Edit profile")
        }
        .contentShape(Rectangle())
    }

    /// setup() runs from the app's `.task`, so its failure may land before or
    /// after this view appears.
    private func surfaceVPNSetupError() {
        guard let error = vpn.lastError else { return }
        vpn.lastError = nil
        errorMessage = error
    }

    private func toggleVPN() {
        guard !vpn.status.isTransitioning else { return }
        if vpn.status.isConnected {
            Task { await vpn.stop() }
            return
        }
        Task {
            do {
                try await vpn.start()
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    /// The tunnel extension cannot pass errors through NEVPNConnection, so it
    /// writes them into the App Group and the app shows them once.
    private func consumeTunnelError() {
        guard let url = AppGroupConfig.containerURL?.appendingPathComponent("tunnel_error.txt"),
              let text = try? String(contentsOf: url, encoding: .utf8),
              !text.isEmpty else { return }
        try? FileManager.default.removeItem(at: url)
        errorMessage = text
    }
}
