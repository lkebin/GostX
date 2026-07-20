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
    @State private var _selectedCategory: SettingsCategory = .profiles

    // Profiles
    @State private var _selectedProfileId: String? = nil
    @State private var isEditorDirty = false
    @State private var yamlText = ""
    @State private var pendingCategory: SettingsCategory? = nil
    @State private var pendingProfileId: String? = nil
    @State private var showDiscardAlert = false

    // Files
    @StateObject private var fileVM = FileManageViewModel()

    // Logs
    @StateObject private var logVM = LogViewModel()
    @State private var loggingEnabled = AppGroupConfig.loggingEnabled
    @State private var logLevel = AppGroupConfig.logLevel

    // Keep old property names for code that reads them (switch statements etc)
    private var selectedCategory: SettingsCategory { _selectedCategory }
    private var selectedProfileId: String? { _selectedProfileId }

    private var categoryBinding: Binding<SettingsCategory> {
        Binding(
            get: { _selectedCategory },
            set: { newValue in
                if isCurrentEditorDirty && newValue != _selectedCategory {
                    pendingCategory = newValue
                    showDiscardAlert = true
                } else {
                    _selectedCategory = newValue
                }
            }
        )
    }

    private var profileIdBinding: Binding<String?> {
        Binding(
            get: { _selectedProfileId },
            set: { newValue in
                if isCurrentEditorDirty && newValue != _selectedProfileId {
                    pendingProfileId = newValue
                    showDiscardAlert = true
                } else {
                    _selectedProfileId = newValue
                }
            }
        )
    }

    private var isCurrentEditorDirty: Bool {
        switch _selectedCategory {
        case .profiles: return _selectedProfileId != nil && isEditorDirty
        case .files: return fileVM.selectedFileName != nil && fileVM.isFileDirty
        case .logs: return false
        }
    }

    private func saveCurrentEditor() {
        switch _selectedCategory {
        case .profiles:
            if let id = _selectedProfileId {
                ConfigRepository.shared.saveConfig(id, yaml: yamlText)
                if id == ConfigRepository.shared.activeProfileId {
                    AppGroupConfig.writeYaml(yamlText)
                }
            }
        case .files:
            fileVM.saveFileContent()
        case .logs:
            break
        }
    }

    private func applyPendingNavigation() {
        if let cat = pendingCategory {
            _selectedCategory = cat
            pendingCategory = nil
        }
        if let pid = pendingProfileId {
            _selectedProfileId = pid
            pendingProfileId = nil
        }
    }

    var body: some View {
        NavigationSplitView {
            // Sidebar
            List(selection: categoryBinding) {
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
                ProfileListView(selectedProfileId: profileIdBinding)
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
                    YamlEditorView(profileId: profileId, isDirty: $isEditorDirty, yamlText: $yamlText)
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
        .alert(NSLocalizedString("Unsaved Changes", comment: ""),
               isPresented: $showDiscardAlert) {
            Button(NSLocalizedString("Save", comment: "")) {
                saveCurrentEditor()
                applyPendingNavigation()
            }
            Button(NSLocalizedString("Discard", comment: "")) {
                applyPendingNavigation()
            }
            Button(NSLocalizedString("Cancel", comment: ""), role: .cancel) {
                pendingCategory = nil
                pendingProfileId = nil
            }
        } message: {
            Text(NSLocalizedString("Do you want to save the changes you made before leaving?", comment: ""))
        }
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
    @Binding var isDirty: Bool
    @Binding var yamlText: String
    @StateObject private var repo = ConfigRepository.shared
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
