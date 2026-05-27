package com.gostx.data

import android.content.SharedPreferences

private const val KEY_PROFILES = "config_profile_list"
private const val KEY_ACTIVE = "config_active_profile"
const val DEFAULT_PROFILE_ID = "default"

val DEFAULT_YAML = """
services:
  - name: socks5-outbound
    addr: :1080
    handler:
      type: socks5
    listener:
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
