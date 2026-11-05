import SwiftUI

struct AddProfileSheet: View {
    let existingNames: Set<String>
    let onConfirm: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var errorText: String?

    var body: some View {
        NavigationStack {
            Form {
                TextField("Profile name", text: $name)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                if let errorText {
                    Text(errorText)
                        .foregroundStyle(.red)
                        .font(.footnote)
                }
            }
            .navigationTitle("New Profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") { submit() }
                        .disabled(ProfileNameValidator.trimmed(name).isEmpty)
                }
            }
        }
    }

    private func submit() {
        if let error = ProfileNameValidator.error(name: name, existing: existingNames) {
            errorText = error
            return
        }
        onConfirm(ProfileNameValidator.trimmed(name))
    }
}
