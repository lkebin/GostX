package com.gostx

import android.content.SharedPreferences
import com.gostx.data.ConfigRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class ConfigRepositoryTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var repo: ConfigRepository

    @Before fun setup() {
        editor = mock {
            on { putString(any(), any()) } doReturn mock
            on { remove(any()) } doReturn mock
        }
        prefs = mock {
            on { edit() } doReturn editor
            on { getString(any(), anyOrNull()) } doReturn null
        }
        repo = ConfigRepository(prefs)
    }

    @Test fun `getActiveConfig returns default YAML when nothing saved`() {
        val config = repo.getActiveConfig()
        assertTrue("Default config must contain 'services:'", config.contains("services:"))
    }

    @Test fun `saveConfig calls putString with correct key`() {
        repo.saveConfig("p1", "services:\n  - name: test")
        verify(editor).putString("config_profile_p1", "services:\n  - name: test")
        verify(editor).apply()
    }

    @Test fun `getProfiles returns at least default profile`() {
        assertTrue(repo.getProfiles().isNotEmpty())
    }
}
