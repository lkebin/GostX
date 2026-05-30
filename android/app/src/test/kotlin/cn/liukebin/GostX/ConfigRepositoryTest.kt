package cn.liukebin.GostX

import cn.liukebin.GostX.data.ConfigProfile
import cn.liukebin.GostX.data.ConfigRepository
import cn.liukebin.GostX.data.DEFAULT_PROFILE_ID
import cn.liukebin.GostX.data.DEFAULT_YAML
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

    @Test fun `getProfiles returns default profile as ConfigProfile on first run`() {
        val profiles = repo.getProfiles()
        assertEquals(1, profiles.size)
        assertEquals(ConfigProfile(DEFAULT_PROFILE_ID, DEFAULT_PROFILE_ID), profiles[0])
    }

    @Test fun `getNextDefaultName returns Config 1 when no numbered profiles exist`() {
        assertEquals("Config 1", repo.getNextDefaultName())
    }

    @Test fun `getNextDefaultName increments past existing numbered profiles`() {
        repo.addProfile("Config 1")
        assertEquals("Config 2", repo.getNextDefaultName())
    }

    @Test fun `getNextDefaultName skips non-sequential gaps`() {
        repo.addProfile("Config 1")
        repo.addProfile("Config 2")
        repo.addProfile("Config 3")
        assertEquals("Config 4", repo.getNextDefaultName())
    }

    @Test fun `addProfile returns true for new unique name`() {
        assertTrue(repo.addProfile("MyProfile"))
    }

    @Test fun `addProfile returns false for duplicate name`() {
        repo.addProfile("MyProfile")
        assertFalse(repo.addProfile("MyProfile"))
    }

    @Test fun `addProfile returns false for duplicate of existing default`() {
        assertFalse(repo.addProfile(DEFAULT_PROFILE_ID))
    }

    @Test fun `addProfile creates profile with empty yaml`() {
        repo.addProfile("NewConfig")
        assertEquals("", repo.getConfig("NewConfig"))
    }

    @Test fun `addProfile makes profile appear in getProfiles`() {
        repo.addProfile("NewConfig")
        assertTrue(repo.getProfiles().any { it.id == "NewConfig" })
    }

    @Test fun `deleteProfile of non-active does not change active`() {
        repo.addProfile("Other")
        val activeBefore = repo.getActiveProfileId()
        repo.deleteProfile("Other")
        assertEquals(activeBefore, repo.getActiveProfileId())
    }

    @Test fun `deleteProfile of active profile switches active to first remaining`() {
        repo.addProfile("Second")
        repo.setActiveProfile("Second")
        repo.deleteProfile("Second")
        assertEquals(DEFAULT_PROFILE_ID, repo.getActiveProfileId())
    }

    @Test fun `deleteProfile removes profile from list`() {
        repo.addProfile("ToDelete")
        repo.deleteProfile("ToDelete")
        assertFalse(repo.getProfiles().any { it.id == "ToDelete" })
    }

    @Test fun `profilesFlow initial value reflects stored profiles`() {
        assertEquals(repo.getProfiles(), repo.profilesFlow.value)
    }

    @Test fun `profilesFlow updates after addProfile`() {
        repo.addProfile("New")
        assertTrue(repo.profilesFlow.value.any { it.id == "New" })
    }

    @Test fun `profilesFlow updates after deleteProfile`() {
        repo.addProfile("ToRemove")
        repo.deleteProfile("ToRemove")
        assertFalse(repo.profilesFlow.value.any { it.id == "ToRemove" })
    }

    @Test fun `activeProfileIdFlow updates after setActiveProfile`() {
        repo.addProfile("Second")
        repo.setActiveProfile("Second")
        assertEquals("Second", repo.activeProfileIdFlow.value)
    }

    @Test fun `getConfig for default profile returns DEFAULT_YAML when not stored`() {
        assertEquals(DEFAULT_YAML, repo.getConfig(DEFAULT_PROFILE_ID))
    }

    @Test fun `saveConfig on existing profile does not duplicate it in list`() {
        val countBefore = repo.getProfiles().size
        repo.saveConfig(DEFAULT_PROFILE_ID, "updated: yaml")
        assertEquals(countBefore, repo.getProfiles().size)
    }

    @Test fun `saveConfig adds profile to list if not already present`() {
        repo.saveConfig("DirectSave", "direct: yaml")
        assertTrue(repo.getProfiles().any { it.id == "DirectSave" })
        assertEquals("direct: yaml", repo.getConfig("DirectSave"))
    }

    @Test fun `getActiveConfig returns yaml for active profile`() {
        repo.addProfile("Second")
        repo.saveConfig("Second", "yaml: content")
        repo.setActiveProfile("Second")
        assertEquals("yaml: content", repo.getActiveConfig())
    }

    @Test fun `saveConfig persists yaml and makes profile retrievable`() {
        repo.saveConfig(DEFAULT_PROFILE_ID, "custom: yaml")
        assertEquals("custom: yaml", repo.getConfig(DEFAULT_PROFILE_ID))
    }

    @Test fun `deleteProfile does nothing when only one profile exists`() {
        repo.deleteProfile(DEFAULT_PROFILE_ID)
        assertEquals(1, repo.getProfiles().size)
        assertEquals(DEFAULT_PROFILE_ID, repo.getProfiles()[0].id)
    }

    @Test fun `activeProfileIdFlow updates when deleteProfile changes active`() {
        repo.addProfile("Second")
        repo.setActiveProfile("Second")
        repo.deleteProfile("Second")
        assertEquals(DEFAULT_PROFILE_ID, repo.activeProfileIdFlow.value)
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
}
