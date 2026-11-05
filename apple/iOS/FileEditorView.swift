import SwiftUI

struct FileEditorView: View {
    let fileName: String

    @StateObject private var vm = FileManageViewModel()
    @State private var text = ""
    @State private var savedText = ""

    private var isDirty: Bool { text != savedText }

    private var fileURL: URL {
        URL(fileURLWithPath: vm.filePath(fileName))
    }

    var body: some View {
        TextEditor(text: $text)
            .font(.system(.footnote, design: .monospaced))
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
            .padding(.horizontal, 12)
            .navigationTitle(fileName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button("Save") { save() }
                        .disabled(!isDirty)
                    ShareLink(item: fileURL)
                }
            }
            .onAppear {
                vm.selectFile(fileName)
                text = vm.fileContent
                savedText = text
            }
    }

    private func save() {
        vm.fileContent = text
        vm.saveFileContent()
        savedText = text
    }
}
