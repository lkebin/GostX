// macos/GostX/ProfileListView.swift
import SwiftUI

@available(macOS 14.0, *)
struct ProfileListView: View {
    @ObservedObject var repo = ConfigRepository.shared
    @Binding var selectedProfileId: String?
    @State private var showAddSheet = false
    @State private var showRenameSheet = false
    @State private var newProfileName = ""
    @State private var renameTargetId: String? = nil

    var body: some View {
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
