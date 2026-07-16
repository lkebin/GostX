// macos/GostX/SettingsView.swift
import SwiftUI

// MARK: - Category

enum SettingsCategory: String, CaseIterable, Identifiable {
    case profiles
    case files
    case logs

    var id: Self { self }

    var icon: String {
        switch self {
        case .profiles: return "doc.text"
        case .files: return "folder"
        case .logs: return "text.alignleft"
        }
    }

    var label: String {
        switch self {
        case .profiles: return NSLocalizedString("Profiles", comment: "")
        case .files: return NSLocalizedString("Files", comment: "")
        case .logs: return NSLocalizedString("Logs", comment: "")
        }
    }
}

// MARK: - SettingsView

@available(macOS 14.0, *)
struct SettingsView: View {
    @State private var selectedCategory: SettingsCategory = .profiles

    // Profiles
    @State private var selectedProfileId: String? = nil

    // Files
    @StateObject private var fileVM = FileManageViewModel()

    // Logs
    @StateObject private var logVM = LogViewModel()
    @State private var loggingEnabled = AppGroupConfig.loggingEnabled
    @State private var logLevel = AppGroupConfig.logLevel

    var body: some View {
        NavigationSplitView {
            // Sidebar
            List(selection: $selectedCategory) {
                ForEach(SettingsCategory.allCases) { category in
                    Label(category.label, systemImage: category.icon)
                        .tag(category)
                }
            }
            .listStyle(.sidebar)
            .navigationSplitViewColumnWidth(min: 140, ideal: 160, max: 200)
        } content: {
            // Content
            switch selectedCategory {
            case .profiles:
                ProfileListView(selectedProfileId: $selectedProfileId)
                    .navigationSplitViewColumnWidth(min: 200, ideal: 220, max: 300)
            case .files:
                FileListView(vm: fileVM)
                    .navigationSplitViewColumnWidth(min: 200, ideal: 220, max: 300)
            case .logs:
                LogOptionsView(loggingEnabled: $loggingEnabled, logLevel: $logLevel)
                    .navigationSplitViewColumnWidth(min: 200, ideal: 220, max: 300)
            }
        } detail: {
            // Detail
            switch selectedCategory {
            case .profiles:
                if let profileId = selectedProfileId {
                    YamlEditorView(profileId: profileId)
                        .id(profileId)
                        .ignoresSafeArea(.container, edges: .top)
                } else {
                    placeholderView(
                        icon: "doc.text.magnifyingglass",
                        text: NSLocalizedString("Select a profile to edit", comment: "")
                    )
                }
            case .files:
                if fileVM.selectedFileName != nil {
                    FileContentView(vm: fileVM)
                } else {
                    placeholderView(
                        icon: "doc.text.magnifyingglass",
                        text: NSLocalizedString("Select a file to view", comment: "")
                    )
                }
            case .logs:
                LogContentView(vm: logVM, loggingEnabled: loggingEnabled)
                    .onAppear {
                        loggingEnabled = AppGroupConfig.loggingEnabled
                    }
            }
        }
        .ignoresSafeArea(.container, edges: .top)
        .frame(minWidth: 700, minHeight: 440)
    }

    private func placeholderView(icon: String, text: String) -> some View {
        VStack {
            Image(systemName: icon)
                .font(.system(size: 28))
                .foregroundColor(Color(nsColor: .tertiaryLabelColor))
            Text(text)
                .font(.system(size: 13))
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - YAML Editor View

@available(macOS 14.0, *)
struct YamlEditorView: View {
    let profileId: String
    @StateObject private var repo = ConfigRepository.shared
    @State private var yamlText: String = ""
    @State private var isDirty = false
    @State private var originalText: String = ""

    var body: some View {
        VStack(spacing: 0) {
            YamlTextView(text: $yamlText)
                .onChange(of: yamlText) { newValue in
                    isDirty = newValue != originalText
                }
                .onAppear {
                    let text = repo.getConfig(profileId)
                    yamlText = text
                    originalText = text
                    isDirty = false
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            Divider()

            HStack {
                Spacer()
                Button(action: {
                    save()
                    originalText = yamlText
                    isDirty = false
                }) {
                    Label(NSLocalizedString("Save", comment: ""), systemImage: "square.and.arrow.down")
                }
                .buttonStyle(.borderless)
                .disabled(!isDirty)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .frame(height: 32)
        }
    }

    private func save() {
        repo.saveConfig(profileId, yaml: yamlText)
        if profileId == repo.activeProfileId {
            AppGroupConfig.writeYaml(yamlText)
        }
    }
}

@available(macOS 14.0, *)
struct SettingsView_Previews: PreviewProvider {
    static var previews: some View {
        SettingsView()
    }
}
