import SwiftUI
import UniformTypeIdentifiers

struct FileManageView: View {
    @StateObject private var vm = FileManageViewModel()
    @State private var showImporter = false
    @State private var renameTarget: String?
    @State private var renameText = ""
    @State private var deleteTarget: String?

    var body: some View {
        Group {
            if vm.files.isEmpty {
                Text("No files")
                    .foregroundStyle(.secondary)
            } else {
                List(vm.files) { file in
                    NavigationLink {
                        FileEditorView(fileName: file.name)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(file.name)
                            Text("\(file.sizeBytes) B")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button(role: .destructive) {
                            deleteTarget = file.name
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                        Button {
                            renameTarget = file.name
                            renameText = file.name
                        } label: {
                            Label("Rename", systemImage: "pencil")
                        }
                    }
                }
            }
        }
        .navigationTitle("Files")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showImporter = true
                } label: {
                    Image(systemName: "square.and.arrow.down")
                }
                .accessibilityLabel("Import file")
            }
        }
        .fileImporter(isPresented: $showImporter,
                      allowedContentTypes: [.data, .plainText, .text]) { result in
            if case .success(let url) = result {
                vm.importFile(from: url)
            }
        }
        .onAppear { vm.refresh() }
        .alert("Error", isPresented: Binding(
            get: { vm.alertMessage != nil },
            set: { if !$0 { vm.alertMessage = nil } }
        )) {
            Button("OK") { vm.alertMessage = nil }
        } message: {
            Text(vm.alertMessage ?? "")
        }
        .alert("Rename File", isPresented: Binding(
            get: { renameTarget != nil },
            set: { if !$0 { renameTarget = nil } }
        )) {
            TextField("File name", text: $renameText)
            Button("Cancel", role: .cancel) { renameTarget = nil }
            Button("Rename") {
                if let target = renameTarget {
                    vm.renameFile(target, to: renameText)
                }
                renameTarget = nil
            }
        }
        .confirmationDialog("Delete File", isPresented: Binding(
            get: { deleteTarget != nil },
            set: { if !$0 { deleteTarget = nil } }
        ), titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                if let target = deleteTarget {
                    vm.deleteFile(target)
                }
                deleteTarget = nil
            }
            Button("Cancel", role: .cancel) { deleteTarget = nil }
        } message: {
            Text(String(format: NSLocalizedString("Are you sure you want to delete \"%@\"? This cannot be undone.", comment: ""), deleteTarget ?? ""))
        }
        .confirmationDialog("File Already Exists", isPresented: Binding(
            get: { vm.pendingOverwrite != nil },
            set: { if !$0 { vm.cancelOverwrite() } }
        ), titleVisibility: .visible) {
            Button("Replace", role: .destructive) { vm.confirmOverwrite() }
            Button("Cancel", role: .cancel) { vm.cancelOverwrite() }
        } message: {
            Text(String(format: NSLocalizedString("A file named \"%@\" already exists. Replace it?", comment: ""), vm.pendingOverwrite?.fileName ?? ""))
        }
    }
}
