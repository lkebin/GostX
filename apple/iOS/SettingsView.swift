import SwiftUI

struct SettingsView: View {
    @State private var loggingEnabled = AppGroupConfig.loggingEnabled
    @State private var logLevel = AppGroupConfig.logLevel
    @State private var logMaxSizeKB = AppGroupConfig.logMaxSizeKB
    @State private var showLevelDialog = false
    @State private var showSizeDialog = false

    private var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
    }

    var body: some View {
        List {
            Section {
                Toggle("Logging", isOn: $loggingEnabled)
                    .onChange(of: loggingEnabled) { _, value in
                        AppGroupConfig.loggingEnabled = value
                    }
                if loggingEnabled {
                    Button {
                        showLevelDialog = true
                    } label: {
                        row("Log Level", logLevel.capitalized)
                    }
                    Button {
                        showSizeDialog = true
                    } label: {
                        row("Max Log Size", "\(logMaxSizeKB) KB")
                    }
                }
            } header: {
                Text("Logging")
            } footer: {
                Text("Restart the VPN to apply logging changes.")
            }

            Section("Files") {
                NavigationLink("File Manager") { FileManageView() }
            }

            Section {
                Text(String(format: NSLocalizedString("Version %@", comment: ""), version))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("Log Level", isPresented: $showLevelDialog, titleVisibility: .visible) {
            ForEach(AppGroupConfig.logLevelOptions, id: \.self) { level in
                Button(level.capitalized) {
                    logLevel = level
                    AppGroupConfig.logLevel = level
                }
            }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("Max Log Size", isPresented: $showSizeDialog, titleVisibility: .visible) {
            ForEach(AppGroupConfig.logMaxSizeOptions, id: \.self) { size in
                Button("\(size) KB") {
                    logMaxSizeKB = size
                    AppGroupConfig.logMaxSizeKB = size
                }
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    private func row(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title).foregroundStyle(.primary)
            Spacer()
            Text(value).foregroundStyle(.secondary)
            Image(systemName: "chevron.right")
                .font(.footnote)
                .foregroundStyle(.tertiary)
        }
    }
}
