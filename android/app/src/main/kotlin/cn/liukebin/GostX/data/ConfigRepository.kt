package cn.liukebin.GostX.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

private const val KEY_PROFILES = "config_profile_list"
private const val KEY_ACTIVE = "config_active_profile"
private const val KEY_LOGGING_ENABLED = "logging_enabled"
private const val KEY_APP_FILTER_MODE = "app_filter_mode"
private const val KEY_APP_FILTER_PACKAGES = "app_filter_packages"
const val DEFAULT_PROFILE_ID = "default"

enum class AppFilterMode { BLACKLIST, WHITELIST }

/**
 * Represents a named VPN configuration profile.
 *
 * [id] is a stable opaque key (UUID for new profiles, legacy name-as-id for
 * profiles created before the UUID migration). [name] is the user-visible label
 * and can be changed freely without affecting [id].
 */
data class ConfigProfile(val id: String, val name: String)

val DEFAULT_YAML = """
# GostX VPN 配置
# 使用 tungo 模式：gVisor 直接处理 TUN 数据包，无需额外监听端口
#
# ⚠️  metadata 位置很重要：
#   - connector 的参数（如 SS 的 method/password）放在 connector.metadata 下
#   - dialer 的参数（如 WSS 的 path、host）放在 dialer.metadata 下
#   - 不要把 dialer 参数放在节点级别的 metadata 下，否则会被忽略
#
# 示例 1: Shadowsocks over TCP（最简单）
services:
  - name: vpn
    addr: :0
    handler:
      type: tungo
      chain: upstream
    listener:
      type: tungo

chains:
  - name: upstream
    hops:
      - name: hop0
        nodes:
          - name: server
            addr: 您的代理服务器:端口
            connector:
              type: ss
              metadata:
                method: chacha20-ietf-poly1305
                password: 您的密码
            dialer:
              type: tcp

# 示例 2: HTTP CONNECT over WebSocket (WSS)
# chains:
#   - name: upstream
#     hops:
#       - name: hop0
#         nodes:
#           - name: server
#             addr: your.server.com:443
#             connector:
#               type: http
#             dialer:
#               type: wss
#               metadata:
#                 path: /your-secret-path   # ← 必须在 dialer.metadata 下，不是节点级别
""".trimIndent()

class ConfigRepository(private val prefs: SharedPreferences) {

    private val _profilesFlow = MutableStateFlow<List<ConfigProfile>>(emptyList())
    val profilesFlow: StateFlow<List<ConfigProfile>> = _profilesFlow.asStateFlow()

    private val _activeProfileIdFlow = MutableStateFlow(DEFAULT_PROFILE_ID)
    val activeProfileIdFlow: StateFlow<String> = _activeProfileIdFlow.asStateFlow()

    private val _loggingEnabledFlow = MutableStateFlow(
        prefs.getBoolean(KEY_LOGGING_ENABLED, false)
    )
    val loggingEnabledFlow: StateFlow<Boolean> = _loggingEnabledFlow.asStateFlow()

    var loggingEnabled: Boolean
        get() = _loggingEnabledFlow.value
        set(value) {
            prefs.edit().putBoolean(KEY_LOGGING_ENABLED, value).apply()
            _loggingEnabledFlow.value = value
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
        _activeProfileIdFlow.value = prefs.getString(KEY_ACTIVE, DEFAULT_PROFILE_ID) ?: DEFAULT_PROFILE_ID
    }

    fun getProfiles(): List<ConfigProfile> = _profilesFlow.value

    fun getActiveProfileId(): String = _activeProfileIdFlow.value

    fun setActiveProfile(id: String) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
        _activeProfileIdFlow.value = id
    }

    fun getConfig(profileId: String): String =
        prefs.getString("config_profile_$profileId", null)
            ?: if (profileId == DEFAULT_PROFILE_ID) DEFAULT_YAML else ""

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
        prefs.edit()
            .putString(KEY_PROFILES, newList.joinToString(",") { it.id })
            .putString("config_profile_name_$id", name)
            .putString("config_profile_$id", "")
            .apply()
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
        if (current.size <= 1) return
        val newList = current.filter { it.id != profileId }
        val editor = prefs.edit()
            .putString(KEY_PROFILES, newList.joinToString(",") { it.id })
            .remove("config_profile_$profileId")
            .remove("config_profile_name_$profileId")
        if (_activeProfileIdFlow.value == profileId) {
            val newActive = newList.first().id
            editor.putString(KEY_ACTIVE, newActive)
            _activeProfileIdFlow.value = newActive
        }
        editor.apply()
        _profilesFlow.value = newList
    }

    private fun loadProfilesFromPrefs(): List<ConfigProfile> {
        val raw = prefs.getString(KEY_PROFILES, null)
            ?: return listOf(ConfigProfile(DEFAULT_PROFILE_ID, DEFAULT_PROFILE_ID))
        return raw.split(",").filter { it.isNotBlank() }.map { id ->
            val name = prefs.getString("config_profile_name_$id", null) ?: id
            ConfigProfile(id, name)
        }
    }
}
