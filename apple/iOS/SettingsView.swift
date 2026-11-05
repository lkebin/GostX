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
                        row("Log Level", levelName(logLevel))
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
                Button {
                    logLevel = level
                    AppGroupConfig.logLevel = level
                } label: {
                    Text(verbatim: levelName(level))
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

    /// Localised name for a `AppGroupConfig.logLevelOptions` value.
    private func levelName(_ level: String) -> String {
        switch level {
        case "error": return NSLocalizedString("Error", comment: "Log level")
        case "warn": return NSLocalizedString("Warning", comment: "Log level")
        case "info": return NSLocalizedString("Info", comment: "Log level")
        case "debug": return NSLocalizedString("Debug", comment: "Log level")
        case "trace": return NSLocalizedString("Trace", comment: "Log level")
        default: return level.capitalized
        }
    }

    /// `title` is a localisation key; `value` is already localised data, so it
    /// must be rendered verbatim instead of being looked up again.
    private func row(_ title: LocalizedStringKey, _ value: String) -> some View {
        HStack {
            Text(title).foregroundStyle(.primary)
            Spacer()
            Text(verbatim: value).foregroundStyle(.secondary)
            Image(systemName: "chevron.right")
                .font(.footnote)
                .foregroundStyle(.tertiary)
        }
    }
}
