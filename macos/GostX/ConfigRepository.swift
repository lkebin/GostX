// macos/GostX/ConfigRepository.swift
import Foundation

// MARK: - Profile Model

struct Profile: Identifiable, Codable {
    var id: String
    var name: String
    private var config: String

    init(id: String = UUID().uuidString, name: String, config: String = defaultGostYAML) {
        self.id = id
        self.name = name
        self.config = config
    }

    var yamlConfig: String {
        get { config }
        set { config = newValue }
    }
}

// MARK: - ConfigRepository

class ConfigRepository: ObservableObject {
    static let shared = ConfigRepository()

    private let defaults = UserDefaults.standard
    private let profilesKey = "gost_profiles_v2"
    private let activeProfileKey = "gost_active_profile_id"

    @Published var profiles: [Profile] = []
    @Published var activeProfileId: String?

    private init() {
        load()
        ensureDefaultProfile()
    }

    // MARK: - Persistence

    private func load() {
        guard let data = defaults.data(forKey: profilesKey),
              let decoded = try? JSONDecoder().decode([Profile].self, from: data)
        else { return }
        profiles = decoded
        activeProfileId = defaults.string(forKey: activeProfileKey)
    }

    private func save() {
        if let data = try? JSONEncoder().encode(profiles) {
            defaults.set(data, forKey: profilesKey)
        }
        defaults.set(activeProfileId, forKey: activeProfileKey)
    }

    private func ensureDefaultProfile() {
        guard profiles.isEmpty else { return }
        let defaultProfile = Profile(name: "Default", config: defaultGostYAML)
        profiles = [defaultProfile]
        activeProfileId = defaultProfile.id
        save()
    }

    // MARK: - Profile CRUD

    @discardableResult
    func addProfile(name: String) -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        let profile = Profile(name: trimmed)
        profiles.append(profile)
        save()
        return profile.id
    }

    func deleteProfile(_ id: String) {
        profiles.removeAll { $0.id == id }
        if activeProfileId == id {
            activeProfileId = profiles.first?.id
            syncActiveToAppGroup()
        }
        save()
    }

    func renameProfile(_ id: String, newName: String) -> Bool {
        let trimmed = newName.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty,
              let index = profiles.firstIndex(where: { $0.id == id })
        else { return false }
        profiles[index].name = trimmed
        save()
        return true
    }

    // MARK: - Config

    func getConfig(_ profileId: String) -> String {
        profiles.first(where: { $0.id == profileId })?.yamlConfig ?? defaultGostYAML
    }

    func saveConfig(_ profileId: String, yaml: String) {
        guard let index = profiles.firstIndex(where: { $0.id == profileId }) else { return }
        profiles[index].yamlConfig = yaml
        save()
        if profileId == activeProfileId {
            AppGroupConfig.writeYaml(yaml)
        }
    }

    // MARK: - Active Profile

    func setActiveProfile(_ id: String) {
        guard profiles.contains(where: { $0.id == id }) else { return }
        activeProfileId = id
        save()
        syncActiveToAppGroup()
    }

    var activeConfig: String {
        guard let id = activeProfileId,
              let profile = profiles.first(where: { $0.id == id })
        else { return defaultGostYAML }
        return profile.yamlConfig
    }

    private func syncActiveToAppGroup() {
        AppGroupConfig.writeYaml(activeConfig)
    }
}
