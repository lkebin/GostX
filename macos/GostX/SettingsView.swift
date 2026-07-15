// macos/GostX/SettingsView.swift
import SwiftUI

@available(macOS 14.0, *)
struct SettingsView: View {
    @StateObject private var repo = ConfigRepository.shared
    @State private var selectedProfileId: String? = nil
    @State private var showAddSheet = false
    @State private var showRenameSheet = false
    @State private var newProfileName = ""
    @State private var renameTargetId: String? = nil
    @State private var selectedTab = 0

    var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 0) {
                HStack(spacing: 0) {
                    tabItem(icon: "doc.text", title: NSLocalizedString("Profiles", comment: ""), index: 0)
                    tabItem(icon: "folder", title: NSLocalizedString("Files", comment: ""), index: 1)
                    tabItem(icon: "text.alignleft", title: NSLocalizedString("Logs", comment: ""), index: 2)
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 4)
            }

            Divider()

            if selectedTab == 0 {
                profilesTab
            } else if selectedTab == 1 {
                FileManageView()
            } else {
                LogView()
            }
        }
        .frame(minWidth: 700, minHeight: 440)
    }

    private func tabItem(icon: String, title: String, index: Int) -> some View {
        let isSelected = selectedTab == index
        return Button(action: { selectedTab = index }) {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 18, weight: .regular))
                Text(title)
                    .font(.system(size: 10))
            }
            .frame(width: 68)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
            .background(isSelected ? Color.accentColor.opacity(0.15) : Color.clear)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .foregroundColor(isSelected ? Color.accentColor : .secondary)
        }
        .buttonStyle(.plain)
    }

    private var profilesTab: some View {
        HStack(spacing: 0) {
            VStack(spacing: 0) {
                List(selection: $selectedProfileId) {
                    ForEach(repo.profiles) { profile in
                        Label(profile.name, systemImage: "doc.text")
                            .tag(profile.id)
                            .contextMenu {
                                Button(NSLocalizedString("Rename...", comment: "")) {
                                    renameTargetId = profile.id
                                    newProfileName = profile.name
                                    showRenameSheet = true
                                }
                                Divider()
                                Button(NSLocalizedString("Delete...", comment: ""), role: .destructive) {
                                    repo.deleteProfile(profile.id)
                                }
                            }
                    }
                }
                .listStyle(.plain)

                Divider()

                HStack {
                    Button(action: { showAddSheet = true }) {
                        Label(NSLocalizedString("Add Profile", comment: ""), systemImage: "plus")
                    }
                    .buttonStyle(.borderless)
                    Spacer()
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .frame(height: 32)
            }
            .frame(width: 200)

            Divider()

            if let profileId = selectedProfileId {
                YamlEditorView(profileId: profileId)
                    .id(profileId)
            } else {
                VStack {
                    Image(systemName: "doc.text.magnifyingglass")
                        .font(.system(size: 28))
                        .foregroundColor(Color(nsColor: .tertiaryLabelColor))
                    Text(NSLocalizedString("Select a profile to edit", comment: ""))
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .onAppear {
            if selectedProfileId == nil, let first = repo.profiles.first {
                selectedProfileId = first.id
            }
        }
        .sheet(isPresented: $showAddSheet) {
            addProfileSheet
        }
        .sheet(isPresented: $showRenameSheet) {
            renameProfileSheet
        }
    }

    private var addProfileSheet: some View {
        VStack(spacing: 16) {
            Text(NSLocalizedString("New Profile", comment: "")).font(.headline)
            TextField(NSLocalizedString("Profile name", comment: ""), text: $newProfileName)
                .textFieldStyle(.roundedBorder)
                .frame(width: 250)
            HStack {
                Button(NSLocalizedString("Cancel", comment: "")) { showAddSheet = false }
                    .keyboardShortcut(.cancelAction)
                Button(NSLocalizedString("Add", comment: "")) {
                    if !newProfileName.isEmpty {
                        let newId = repo.addProfile(name: newProfileName)
                        if let id = newId { selectedProfileId = id }
                        newProfileName = ""
                        showAddSheet = false
                    }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(newProfileName.isEmpty)
            }
        }
        .padding()
        .frame(width: 300, height: 140)
    }

    private var renameProfileSheet: some View {
        VStack(spacing: 16) {
            Text(NSLocalizedString("Rename Profile", comment: "")).font(.headline)
            TextField(NSLocalizedString("Profile name", comment: ""), text: $newProfileName)
                .textFieldStyle(.roundedBorder)
                .frame(width: 250)
            HStack {
                Button(NSLocalizedString("Cancel", comment: "")) { showRenameSheet = false }
                    .keyboardShortcut(.cancelAction)
                Button(NSLocalizedString("Rename", comment: "")) {
                    if let id = renameTargetId, !newProfileName.isEmpty {
                        _ = repo.renameProfile(id, newName: newProfileName)
                        newProfileName = ""
                        renameTargetId = nil
                        showRenameSheet = false
                    }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(newProfileName.isEmpty)
            }
        }
        .padding()
        .frame(width: 300, height: 140)
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
