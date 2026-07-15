// macos/GostX/LogOptionsView.swift
import SwiftUI

@available(macOS 14.0, *)
struct LogOptionsView: View {
    @State private var loggingEnabled = AppGroupConfig.loggingEnabled
    @State private var logLevel = AppGroupConfig.logLevel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Toggle(isOn: $loggingEnabled) {
                    Text(NSLocalizedString("Logging", comment: ""))
                        .font(.system(size: 12, weight: .medium))
                }
                .toggleStyle(.switch)
                .controlSize(.small)
                .onChange(of: loggingEnabled) { newValue in
                    AppGroupConfig.loggingEnabled = newValue
                    AppLogger.log(.info, "Logging \(newValue ? "enabled" : "disabled")")
                }
            }

            if loggingEnabled {
                VStack(alignment: .leading, spacing: 6) {
                    Text(NSLocalizedString("Log Level", comment: ""))
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)

                    Picker("", selection: $logLevel) {
                        ForEach(AppGroupConfig.logLevelOptions, id: \.self) { level in
                            Text(level.capitalized).tag(level)
                        }
                    }
                    .pickerStyle(.radioGroup)
                    .onChange(of: logLevel) { newValue in
                        AppGroupConfig.logLevel = newValue
                        AppLogger.log(.info, "Log level: \(newValue)")
                    }
                }
            }

            Text(NSLocalizedString("Restart VPN to apply", comment: ""))
                .font(.system(size: 9))
                .foregroundColor(.secondary)

            Spacer()
        }
        .padding(16)
    }
}
