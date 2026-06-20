package cn.liukebin.gostx.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

private const val KEY_PROFILES = "config_profile_list"
private const val KEY_ACTIVE = "config_active_profile"
private const val KEY_LOGGING_ENABLED = "logging_enabled"
private const val KEY_LOG_LEVEL = "log_level"
private const val KEY_LOG_MAX_SIZE_KB = "log_max_size_kb"
private const val KEY_APP_FILTER_ENABLED = "app_filter_enabled"
private const val KEY_APP_FILTER_MODE = "app_filter_mode"
private const val KEY_APP_FILTER_PACKAGES = "app_filter_packages"
enum class AppFilterMode { BLACKLIST, WHITELIST }

/**
 * Represents a named VPN configuration profile.
 *
 * [id] is a stable opaque key (UUID for new profiles, legacy name-as-id for
 * profiles created before the UUID migration). [name] is the user-visible label
 * and can be changed freely without affecting [id].
 */
data class ConfigProfile(val id: String, val name: String)

class ConfigRepository(private val prefs: SharedPreferences) {

    private val _profilesFlow = MutableStateFlow<List<ConfigProfile>>(emptyList())
    val profilesFlow: StateFlow<List<ConfigProfile>> = _profilesFlow.asStateFlow()

    private val _activeProfileIdFlow = MutableStateFlow("")
    val activeProfileIdFlow: StateFlow<String> = _activeProfileIdFlow.asStateFlow()

    // Backward compat: migrate from old boolean logging_enabled to log_level string.
    private val initialLogLevel: String = run {
        val existing = prefs.getString(KEY_LOG_LEVEL, null)
        if (existing != null) return@run existing
        val wasEnabled = prefs.getBoolean(KEY_LOGGING_ENABLED, false)
        if (wasEnabled) "info" else "off"
    }

    private val _logLevelFlow = MutableStateFlow(initialLogLevel)
    val logLevelFlow: StateFlow<String> = _logLevelFlow.asStateFlow()

    private var lastNonOffLevel = if (initialLogLevel != "off") initialLogLevel else "info"

    var logLevel: String
        get() = _logLevelFlow.value
        set(value) {
            prefs.edit().putString(KEY_LOG_LEVEL, value).apply()
            _logLevelFlow.value = value
            if (value != "off") lastNonOffLevel = value
        }

    // Derived from logLevel for backward compat with existing consumers.
    private val _loggingEnabledFlow = MutableStateFlow(initialLogLevel != "off")
    val loggingEnabledFlow: StateFlow<Boolean> = _loggingEnabledFlow.asStateFlow()

    var loggingEnabled: Boolean
        get() = logLevel != "off"
        set(value) {
            logLevel = if (value) lastNonOffLevel else "off"
            _loggingEnabledFlow.value = value
        }

    // Valid options in KB: 512, 1024, 2048, 5120. Default 2048 (2 MiB).
    private val _logMaxSizeKbFlow = MutableStateFlow(
        prefs.getInt(KEY_LOG_MAX_SIZE_KB, 2048)
    )
    val logMaxSizeKbFlow: StateFlow<Int> = _logMaxSizeKbFlow.asStateFlow()

    var logMaxSizeKb: Int
        get() = _logMaxSizeKbFlow.value
        set(value) {
            prefs.edit().putInt(KEY_LOG_MAX_SIZE_KB, value).apply()
            _logMaxSizeKbFlow.value = value
        }

    private val _appFilterEnabledFlow = MutableStateFlow(
        prefs.getBoolean(KEY_APP_FILTER_ENABLED, false)
    )
    val appFilterEnabledFlow: StateFlow<Boolean> = _appFilterEnabledFlow.asStateFlow()

    var appFilterEnabled: Boolean
        get() = _appFilterEnabledFlow.value
        set(value) {
            prefs.edit().putBoolean(KEY_APP_FILTER_ENABLED, value).apply()
            _appFilterEnabledFlow.value = value
        }

    private val _appFilterModeFlow = MutableStateFlow(
        when (prefs.getString(KEY_APP_FILTER_MODE, null)) {
            "whitelist" -> AppFilterMode.WHITELIST
            else -> AppFilterMode.BLACKLIST
        }
    )
    val appFilterModeFlow: StateFlow<AppFilterMode> = _appFilterModeFlow.asStateFlow()

    var appFilterMode: AppFilterMode
        get() = _appFilterModeFlow.value
        set(value) {
            prefs.edit()
                .putString(KEY_APP_FILTER_MODE, if (value == AppFilterMode.WHITELIST) "whitelist" else "blacklist")
                .apply()
            _appFilterModeFlow.value = value
        }

    private val _appFilterListFlow = MutableStateFlow<Set<String>>(
        prefs.getStringSet(KEY_APP_FILTER_PACKAGES, emptySet())?.toSet() ?: emptySet()
    )
    val appFilterListFlow: StateFlow<Set<String>> = _appFilterListFlow.asStateFlow()

    var appFilterList: Set<String>
        get() = _appFilterListFlow.value
        set(value) {
            prefs.edit().putStringSet(KEY_APP_FILTER_PACKAGES, value.toMutableSet()).apply()
            _appFilterListFlow.value = value
        }

    init {
        _profilesFlow.value = loadProfilesFromPrefs()
        _activeProfileIdFlow.value = prefs.getString(KEY_ACTIVE, "") ?: ""
    }

    fun getProfiles(): List<ConfigProfile> = _profilesFlow.value

    fun getActiveProfileId(): String = _activeProfileIdFlow.value

    fun setActiveProfile(id: String) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
        _activeProfileIdFlow.value = id
    }

    fun getConfig(profileId: String): String =
        prefs.getString("config_profile_$profileId", null)
            ?: ""

    fun getActiveConfig(): String = getConfig(getActiveProfileId())

    fun saveConfig(profileId: String, yaml: String) {
        val editor = prefs.edit().putString("config_profile_$profileId", yaml)
        val profiles = _profilesFlow.value
        if (profiles.none { it.id == profileId }) {
            val newList = profiles + ConfigProfile(profileId, profileId)
            editor.putString(KEY_PROFILES, newList.joinToString(",") { it.id })
            editor.apply()
            _profilesFlow.value = newList
        } else {
            editor.apply()
        }
    }

    fun getNextDefaultName(): String {
        val existingNames = _profilesFlow.value.map { it.name }.toSet()
        var n = 1
        while ("Config $n" in existingNames) n++
        return "Config $n"
    }

    fun addProfile(name: String): String? {
        val current = _profilesFlow.value
        if (current.any { it.name == name }) return null
        if (name.contains(',')) return null
        val id = UUID.randomUUID().toString()
        val newList = current + ConfigProfile(id, name)
        val editor = prefs.edit()
            .putString(KEY_PROFILES, newList.joinToString(",") { it.id })
            .putString("config_profile_name_$id", name)
            .putString("config_profile_$id", "")
        if (current.isEmpty()) {
            editor.putString(KEY_ACTIVE, id)
            _activeProfileIdFlow.value = id
        }
        editor.apply()
        _profilesFlow.value = newList
        return id
    }

    fun renameProfile(oldId: String, newName: String): Boolean {
        if (newName.contains(',')) return false
        val current = _profilesFlow.value
        val profile = current.find { it.id == oldId } ?: return false
        if (newName == profile.name) return true
        if (current.any { it.name == newName && it.id != oldId }) return false
        val newList = current.map { if (it.id == oldId) ConfigProfile(oldId, newName) else it }
        prefs.edit().putString("config_profile_name_$oldId", newName).apply()
        _profilesFlow.value = newList
        return true
    }

    fun deleteProfile(profileId: String) {
        val current = _profilesFlow.value
        val newList = current.filter { it.id != profileId }
        val editor = prefs.edit()
            .putString(KEY_PROFILES, newList.joinToString(",") { it.id })
            .remove("config_profile_$profileId")
            .remove("config_profile_name_$profileId")
        if (_activeProfileIdFlow.value == profileId) {
            val newActive = newList.firstOrNull()?.id ?: ""
            editor.putString(KEY_ACTIVE, newActive)
            _activeProfileIdFlow.value = newActive
        }
        editor.apply()
        _profilesFlow.value = newList
    }

    private fun loadProfilesFromPrefs(): List<ConfigProfile> {
        val raw = prefs.getString(KEY_PROFILES, null)
            ?: return emptyList()
        return raw.split(",").filter { it.isNotBlank() }.map { id ->
            val name = prefs.getString("config_profile_name_$id", null) ?: id
            ConfigProfile(id, name)
        }
    }
}
