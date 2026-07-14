// macos/GostX/SettingsView.swift
import SwiftUI
import HighlightedTextEditor

let reOpts = NSRegularExpression.Options([.anchorsMatchLines])
let yamlKeyRule   = try! NSRegularExpression(
    pattern: "^(\\s*)(services|chains|hops|name|addr|handler|listener|connector|dialer|type|chain|auth|tls|metadata|bypass|resolver|hosts|retries|timeout)\\s*:",
    options: reOpts)
let yamlCommentRule = try! NSRegularExpression(pattern: "^\\s*#.*", options: reOpts)

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
        TabView(selection: $selectedTab) {
            profilesTab
                .tabItem {
                    Label(NSLocalizedString("Profiles", comment: ""), systemImage: "doc.text")
                }
                .tag(0)
            FileManageView()
                .tabItem {
                    Label(NSLocalizedString("Files", comment: ""), systemImage: "folder")
                }
                .tag(1)
        }
        .frame(minWidth: 700, minHeight: 440)
    }

    private var profilesTab: some View {
        NavigationSplitView {
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

                Button(action: { showAddSheet = true }) {
                    Label(NSLocalizedString("Add Profile", comment: ""), systemImage: "plus")
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.borderless)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
            }
            .frame(maxHeight: .infinity)
            .navigationSplitViewColumnWidth(min: 180, ideal: 220)
        } detail: {
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

    private let rules: [HighlightRule] = [
        HighlightRule(
            pattern: yamlCommentRule,
            formattingRule: TextFormattingRule(key: .foregroundColor, value: NSColor.systemGray)
        ),
        HighlightRule(
            pattern: yamlKeyRule,
            formattingRules: [
                TextFormattingRule(fontTraits: .bold),
                TextFormattingRule(key: .foregroundColor, value: NSColor.systemBlue),
            ]
        ),
    ]

    var body: some View {
        VStack(spacing: 0) {
            HighlightedTextEditor(text: $yamlText, highlightRules: rules)
                .introspect { editor in
                    editor.textView.allowsUndo = true
                    editor.textView.breakUndoCoalescing()
                    editor.textView.font = NSFont.monospacedSystemFont(ofSize: 12, weight: .regular)
                }
                .onChange(of: yamlText) { newValue in
                    save()
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            Divider()

            Text("gost v3 YAML configuration — https://gost.run/docs/")
                .font(.system(size: 10))
                .foregroundColor(Color(nsColor: .tertiaryLabelColor))
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
        }
        .onAppear {
            yamlText = repo.getConfig(profileId)
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
