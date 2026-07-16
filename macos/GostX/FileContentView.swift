// macos/GostX/FileContentView.swift
import SwiftUI

@available(macOS 14.0, *)
struct FileContentView: View {
    @ObservedObject var vm: FileManageViewModel

    var body: some View {
        Group {
            if vm.selectedFileName != nil {
                VStack(spacing: 0) {
                    TextEditor(text: $vm.fileContent)
                        .font(.system(size: 12, design: .monospaced))
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .onChange(of: vm.fileContent) { _ in
                            DispatchQueue.main.async {
                                vm.isFileDirty = vm.fileContent != vm.originalContent
                            }
                        }

                    Divider()

                    HStack {
                        Spacer()
                        Button(action: { vm.saveFileContent() }) {
                            Label(NSLocalizedString("Save", comment: ""), systemImage: "square.and.arrow.down")
                        }
                        .buttonStyle(.borderless)
                        .disabled(!vm.isFileDirty)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .frame(height: 32)
                }
            } else {
                VStack {
                    Image(systemName: "doc.text.magnifyingglass")
                        .font(.system(size: 28))
                        .foregroundColor(Color(nsColor: .tertiaryLabelColor))
                    Text(NSLocalizedString("Select a file to view", comment: ""))
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }
}
