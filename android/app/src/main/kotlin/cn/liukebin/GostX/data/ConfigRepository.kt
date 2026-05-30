package cn.liukebin.GostX.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val KEY_PROFILES = "config_profile_list"
private const val KEY_ACTIVE = "config_active_profile"
const val DEFAULT_PROFILE_ID = "default"

/**
 * Represents a named VPN configuration profile.
 *
 * [id] serves as both the unique key (used in SharedPreferences) and the display name.
 * [id] == [name] by design: profile names are unique, so the name itself is the key.
 * The two-field form exists so callers can reference `profile.name` semantically
 * without coupling to the storage key implementation.
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

    fun addProfile(name: String): Boolean {
        val current = _profilesFlow.value
        if (current.any { it.id == name }) return false
        val newList = current + ConfigProfile(name, name)
        prefs.edit()
            .putString(KEY_PROFILES, newList.joinToString(",") { it.id })
            .putString("config_profile_$name", "")
            .apply()
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
        if (_activeProfileIdFlow.value == profileId) {
            val newActive = newList.first().id
            editor.putString(KEY_ACTIVE, newActive)
            _activeProfileIdFlow.value = newActive
        }
        editor.apply()
        _profilesFlow.value = newList
    }

    private fun loadProfilesFromPrefs(): List<ConfigProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return listOf(ConfigProfile(DEFAULT_PROFILE_ID, DEFAULT_PROFILE_ID))
        return raw.split(",").filter { it.isNotBlank() }.map { ConfigProfile(it, it) }
    }
}
