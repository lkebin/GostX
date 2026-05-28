package com.gostx.data

import android.content.SharedPreferences

private const val KEY_PROFILES = "config_profile_list"
private const val KEY_ACTIVE = "config_active_profile"
const val DEFAULT_PROFILE_ID = "default"

val DEFAULT_YAML = """
# GostX VPN 配置
# tun2socks 将设备所有流量转发到 127.0.0.1:10808
# 在此配置您的 gost 代理链，实现加密隧道转发

services:
  - name: tun
    addr: 127.0.0.1:10808
    handler:
      type: socks5
      chain: upstream
    listener:
      type: tcp

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
