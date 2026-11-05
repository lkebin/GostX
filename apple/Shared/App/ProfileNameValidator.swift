import Foundation

/// Profile name validation matching the Android rules: non-empty, no comma,
/// no duplicate of an existing profile name.
enum ProfileNameValidator {
    static func trimmed(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespaces)
    }

    static func error(name: String, existing: Set<String>) -> String? {
        let value = trimmed(name)
        if value.isEmpty {
            return NSLocalizedString("Profile name can't be empty.", comment: "")
        }
        if value.contains(",") {
            return NSLocalizedString("Profile name can't contain a comma.", comment: "")
        }
        if existing.contains(value) {
            return NSLocalizedString("A profile with that name already exists.", comment: "")
        }
        return nil
    }
}
