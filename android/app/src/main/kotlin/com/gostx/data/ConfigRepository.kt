package com.gostx.data

import android.content.SharedPreferences

private const val KEY_PROFILES = "config_profile_list"
private const val KEY_ACTIVE = "config_active_profile"
const val DEFAULT_PROFILE_ID = "default"

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

    fun getProfiles(): List<String> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return listOf(DEFAULT_PROFILE_ID)
        return raw.split(",").filter { it.isNotBlank() }
    }

    fun getActiveProfileId(): String =
        prefs.getString(KEY_ACTIVE, DEFAULT_PROFILE_ID) ?: DEFAULT_PROFILE_ID

    fun setActiveProfile(id: String) = prefs.edit().putString(KEY_ACTIVE, id).apply()

    fun getConfig(profileId: String): String =
        prefs.getString("config_profile_$profileId", null) ?: DEFAULT_YAML

    fun getActiveConfig(): String = getConfig(getActiveProfileId())

    fun saveConfig(profileId: String, yaml: String) {
        val editor = prefs.edit().putString("config_profile_$profileId", yaml)
        val profiles = getProfiles().toMutableList()
        if (!profiles.contains(profileId)) {
            profiles.add(profileId)
            editor.putString(KEY_PROFILES, profiles.joinToString(","))
        }
        editor.apply()
    }

    fun deleteProfile(profileId: String) {
        if (profileId == DEFAULT_PROFILE_ID) return
        val profiles = getProfiles().toMutableList().also { it.remove(profileId) }
        prefs.edit()
            .putString(KEY_PROFILES, profiles.joinToString(","))
            .remove("config_profile_$profileId")
            .apply()
        if (getActiveProfileId() == profileId) setActiveProfile(DEFAULT_PROFILE_ID)
    }
}
