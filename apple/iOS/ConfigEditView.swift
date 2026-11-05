import SwiftUI
import UIKit

struct ConfigEditView: View {
    let profileId: String

    @ObservedObject private var repo = ConfigRepository.shared
    @Environment(\.dismiss) private var dismiss

    @State private var yaml = ""
    @State private var savedYaml = ""
    @State private var showRename = false
    @State private var renameText = ""
    @State private var renameError: String?
    @State private var showDeleteConfirm = false
    @State private var showDiscardConfirm = false

    private var isDirty: Bool { yaml != savedYaml }

    private var profileName: String {
        repo.profiles.first(where: { $0.id == profileId })?.name ?? "Profile"
    }

    /// iOS can only run configs with a tungo service; warn while editing.
    private var lacksTungo: Bool { !yaml.contains("tungo") }

    var body: some View {
        VStack(spacing: 0) {
            // YamlTextView disables smart punctuation, which TextEditor cannot.
            if lacksTungo {
                Text("This config has no tungo service and won't work on iOS.")
                    .font(.footnote)
                    .foregroundStyle(.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
                    .padding(.vertical, 8)
            }
            YamlTextView(text: $yaml)
        }
        .navigationTitle(profileName)
        .navigationBarTitleDisplayMode(.inline)
        // The custom back button intercepts unsaved edits; hiding the system
        // button also disables the interactive pop gesture that would discard
        // them silently.
        .navigationBarBackButtonHidden(true)
        .interactiveDismissDisabled(isDirty)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button {
                    if isDirty { showDiscardConfirm = true } else { dismiss() }
                } label: {
                    Image(systemName: "chevron.left")
                }
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("Save") { save() }
                    .disabled(!isDirty)
                Menu {
                    Button("Rename…") {
                        renameText = profileName
                        renameError = nil
                        showRename = true
                    }
                    Button("Delete…", role: .destructive) { showDeleteConfirm = true }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .onAppear {
            yaml = repo.getConfig(profileId)
            savedYaml = yaml
        }
        .alert("Rename Profile", isPresented: $showRename) {
            TextField("Profile name", text: $renameText)
            Button("Cancel", role: .cancel) {}
            Button("Rename") { rename() }
        } message: {
            if let renameError {
                Text(renameError)
            }
        }
        .confirmationDialog("Delete this profile?", isPresented: $showDeleteConfirm, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                repo.deleteProfile(profileId)
                dismiss()
            }
        } message: {
            Text(String(format: NSLocalizedString("\"%@\" will be removed. This cannot be undone.", comment: ""), profileName))
        }
        .confirmationDialog("Unsaved Changes", isPresented: $showDiscardConfirm, titleVisibility: .visible) {
            Button("Save") { save(); dismiss() }
            Button("Discard", role: .destructive) { dismiss() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Do you want to save the changes you made before leaving?")
        }
    }

    private func save() {
        repo.saveConfig(profileId, yaml: yaml)
        savedYaml = yaml
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    private func rename() {
        let existing = Set(repo.profiles.filter { $0.id != profileId }.map(\.name))
        if let error = ProfileNameValidator.error(name: renameText, existing: existing) {
            renameError = error
            showRename = true
            return
        }
        _ = repo.renameProfile(profileId, newName: ProfileNameValidator.trimmed(renameText))
        showRename = false
    }
}
