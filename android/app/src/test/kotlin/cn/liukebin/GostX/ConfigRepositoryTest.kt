package cn.liukebin.gostx

import cn.liukebin.gostx.data.AppFilterMode
import cn.liukebin.gostx.data.ConfigProfile
import cn.liukebin.gostx.data.ConfigRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConfigRepositoryTest {
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var repo: ConfigRepository

    @Before fun setup() {
        prefs = FakeSharedPreferences()
        repo = ConfigRepository(prefs)
    }

    @Test fun `getProfiles returns empty list on first run`() {
        assertEquals(0, repo.getProfiles().size)
    }

    @Test fun `getActiveProfileId returns empty string when no profiles`() {
        assertEquals("", repo.getActiveProfileId())
    }

    @Test fun `getActiveConfig returns empty string when no profiles`() {
        assertEquals("", repo.getActiveConfig())
    }

    @Test fun `getConfig returns empty string when not stored`() {
        assertEquals("", repo.getConfig("nonexistent"))
    }

    @Test fun `getNextDefaultName returns Config 1 when no profiles`() {
        assertEquals("Config 1", repo.getNextDefaultName())
    }

    @Test fun `getNextDefaultName increments past existing numbered profiles`() {
        repo.addProfile("Config 1")
        assertEquals("Config 2", repo.getNextDefaultName())
    }

    @Test fun `getNextDefaultName returns next sequential number`() {
        repo.addProfile("Config 1")
        repo.addProfile("Config 2")
        repo.addProfile("Config 3")
        assertEquals("Config 4", repo.getNextDefaultName())
    }

    @Test fun `getNextDefaultName fills gaps in sequence`() {
        repo.addProfile("Config 1")
        repo.addProfile("Config 3")
        assertEquals("Config 2", repo.getNextDefaultName())
    }

    @Test fun `addProfile returns UUID for new unique name`() {
        assertNotNull(repo.addProfile("MyProfile"))
    }

    @Test fun `addProfile returns null for duplicate name`() {
        repo.addProfile("MyProfile")
        assertNull(repo.addProfile("MyProfile"))
    }

    @Test fun `addProfile auto-sets active when first profile`() {
        val id = repo.addProfile("First")!!
        assertEquals(id, repo.getActiveProfileId())
    }

    @Test fun `addProfile does not change active when not first`() {
        val firstId = repo.addProfile("First")!!
        val secondId = repo.addProfile("Second")!!
        assertEquals(firstId, repo.getActiveProfileId())
    }

    @Test fun `addProfile creates profile with empty yaml`() {
        val id = repo.addProfile("NewConfig")!!
        assertEquals("", repo.getConfig(id))
    }

    @Test fun `addProfile makes profile appear in getProfiles`() {
        repo.addProfile("NewConfig")
        assertTrue(repo.getProfiles().any { it.name == "NewConfig" })
    }

    @Test fun `deleteProfile of non-active does not change active`() {
        val firstId = repo.addProfile("First")!!
        val otherId = repo.addProfile("Other")!!
        val activeBefore = repo.getActiveProfileId()
        repo.deleteProfile(otherId)
        assertEquals(activeBefore, repo.getActiveProfileId())
    }

    @Test fun `deleteProfile of active profile switches active to first remaining`() {
        val firstId = repo.addProfile("First")!!
        val secondId = repo.addProfile("Second")!!
        repo.setActiveProfile(secondId)
        repo.deleteProfile(secondId)
        assertEquals(firstId, repo.getActiveProfileId())
    }

    @Test fun `deleteProfile removes profile from list`() {
        val id = repo.addProfile("ToDelete")!!
        repo.deleteProfile(id)
        assertFalse(repo.getProfiles().any { it.name == "ToDelete" })
    }

    @Test fun `deleteProfile of last profile clears activeId`() {
        val id = repo.addProfile("Only")!!
        repo.deleteProfile(id)
        assertTrue(repo.getProfiles().isEmpty())
        assertEquals("", repo.getActiveProfileId())
    }

    @Test fun `profilesFlow initial value reflects stored profiles`() {
        assertEquals(repo.getProfiles(), repo.profilesFlow.value)
    }

    @Test fun `profilesFlow updates after addProfile`() {
        repo.addProfile("New")
        assertTrue(repo.profilesFlow.value.any { it.name == "New" })
    }

    @Test fun `profilesFlow updates after deleteProfile`() {
        val id = repo.addProfile("ToRemove")!!
        repo.deleteProfile(id)
        assertFalse(repo.profilesFlow.value.any { it.name == "ToRemove" })
    }

    @Test fun `activeProfileIdFlow updates after setActiveProfile`() {
        repo.addProfile("First")
        val secondId = repo.addProfile("Second")!!
        repo.setActiveProfile(secondId)
        assertEquals(secondId, repo.activeProfileIdFlow.value)
    }

    @Test fun `saveConfig on existing profile does not duplicate it in list`() {
        repo.addProfile("First")
        val countBefore = repo.getProfiles().size
        repo.saveConfig(repo.getActiveProfileId(), "updated: yaml")
        assertEquals(countBefore, repo.getProfiles().size)
    }

    @Test fun `saveConfig adds profile to list if not already present`() {
        repo.saveConfig("DirectSave", "direct: yaml")
        assertTrue(repo.getProfiles().any { it.id == "DirectSave" })
        assertEquals("direct: yaml", repo.getConfig("DirectSave"))
    }

    @Test fun `getActiveConfig returns yaml for active profile`() {
        val secondId = repo.addProfile("Second")!!
        repo.saveConfig(secondId, "yaml: content")
        repo.setActiveProfile(secondId)
        assertEquals("yaml: content", repo.getActiveConfig())
    }

    @Test fun `saveConfig persists yaml and makes profile retrievable`() {
        val id = repo.addProfile("MyConfig")!!
        repo.saveConfig(id, "custom: yaml")
        assertEquals("custom: yaml", repo.getConfig(id))
    }

    @Test fun `activeProfileIdFlow updates when deleteProfile changes active`() {
        val firstId = repo.addProfile("First")!!
        val secondId = repo.addProfile("Second")!!
        repo.setActiveProfile(secondId)
        repo.deleteProfile(secondId)
        assertEquals(firstId, repo.activeProfileIdFlow.value)
    }

    @Test fun `fake shared preferences getStringSet returns stored set`() {
        val values = mutableSetOf("one", "two")
        prefs.edit().putStringSet("set-key", values).apply()
        assertEquals(values, prefs.getStringSet("set-key", mutableSetOf("default")))
    }

    @Test fun `fake shared preferences clear defers mutation until apply`() {
        prefs.edit().putString("first", "value").putString("second", "value").apply()

        val editor = prefs.edit().clear()

        assertEquals("value", prefs.getString("first", null))
        assertEquals("value", prefs.getString("second", null))

        editor.apply()

        assertNull(prefs.getString("first", null))
        assertNull(prefs.getString("second", null))
    }

    @Test fun `appFilterMode defaults to BLACKLIST`() {
        assertEquals(AppFilterMode.BLACKLIST, repo.appFilterMode)
    }

    @Test fun `appFilterMode can be set to WHITELIST and persists serialized string`() {
        repo.appFilterMode = AppFilterMode.WHITELIST
        assertEquals(AppFilterMode.WHITELIST, repo.appFilterMode)
        assertEquals("whitelist", prefs.getString("app_filter_mode", null))
    }

    @Test fun `appFilterMode serializes BLACKLIST as string`() {
        repo.appFilterMode = AppFilterMode.BLACKLIST
        assertEquals("blacklist", prefs.getString("app_filter_mode", null))
    }

    @Test fun `appFilterModeFlow reflects current mode`() {
        assertEquals(AppFilterMode.BLACKLIST, repo.appFilterModeFlow.value)
        repo.appFilterMode = AppFilterMode.WHITELIST
        assertEquals(AppFilterMode.WHITELIST, repo.appFilterModeFlow.value)
    }

    @Test fun `appFilterMode survives ConfigRepository recreation`() {
        repo.appFilterMode = AppFilterMode.WHITELIST
        assertEquals(AppFilterMode.WHITELIST, ConfigRepository(prefs).appFilterMode)
    }

    @Test fun `appFilterList defaults to empty set`() {
        assertTrue(repo.appFilterList.isEmpty())
    }

    @Test fun `appFilterList can be saved and retrieved`() {
        repo.appFilterList = setOf("com.example.app1", "com.example.app2")
        assertEquals(setOf("com.example.app1", "com.example.app2"), repo.appFilterList)
    }

    @Test fun `appFilterListFlow reflects current list`() {
        assertTrue(repo.appFilterListFlow.value.isEmpty())
        repo.appFilterList = setOf("com.example.app")
        assertEquals(setOf("com.example.app"), repo.appFilterListFlow.value)
    }

    @Test fun `appFilterList survives ConfigRepository recreation`() {
        repo.appFilterList = setOf("com.example.app")
        assertEquals(setOf("com.example.app"), ConfigRepository(prefs).appFilterList)
    }

    @Test fun `appFilterList setter removes packages when list is reduced`() {
        repo.appFilterList = setOf("com.a", "com.b")
        repo.appFilterList = setOf("com.a")
        assertEquals(setOf("com.a"), repo.appFilterList)
    }
}
