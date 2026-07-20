// macos/GostX/LogOptionsView.swift
import SwiftUI

@available(macOS 14.0, *)
struct LogOptionsView: View {
    @Binding var loggingEnabled: Bool
    @Binding var logLevel: String

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(spacing: 0) {
                // Enable Logging toggle row
                HStack {
                    Text(NSLocalizedString("Enable Logging", comment: ""))
                        .font(.system(size: 13))
                    Spacer()
                    Toggle("", isOn: $loggingEnabled)
                        .toggleStyle(.switch)
                        .controlSize(.small)
                        .labelsHidden()
                        .onChange(of: loggingEnabled) { newValue in
                            AppGroupConfig.loggingEnabled = newValue
                            AppLogger.log(.info, "Logging \(newValue ? "enabled" : "disabled")")
                        }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)

                Divider()
                    .padding(.leading, 12)

                // Log Level picker row
                HStack {
                    Text(NSLocalizedString("Log Level", comment: ""))
                        .font(.system(size: 13))
                    Spacer()
                    Picker("", selection: $logLevel) {
                        ForEach(AppGroupConfig.logLevelOptions, id: \.self) { level in
                            Text(level.capitalized).tag(level)
                        }
                    }
                    .pickerStyle(.menu)
                    .labelsHidden()
                    .font(.system(size: 13))
                    .onChange(of: logLevel) { newValue in
                        AppGroupConfig.logLevel = newValue
                        AppLogger.log(.info, "Log level: \(newValue)")
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            }
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color(nsColor: .quaternaryLabelColor).opacity(0.3))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .strokeBorder(Color(nsColor: .separatorColor), lineWidth: 0.5)
            )

            Text(NSLocalizedString("Restart VPN to apply", comment: ""))
                .font(.system(size: 11))
                .foregroundColor(.secondary)
                .padding(.horizontal, 12)

            Spacer()
        }
        .padding(16)
    }
}
