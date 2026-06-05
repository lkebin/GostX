package cn.liukebin.gostx

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import cn.liukebin.gostx.data.ConfigRepository
import cn.liukebin.gostx.ui.config.ConfigScreen
import org.junit.Rule
import org.junit.Test

class ConfigScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun repo(): ConfigRepository {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("config_screen_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return ConfigRepository(prefs)
    }

    @Test fun showsYamlEditorWithDefaultContent() {
        rule.setContent { ConfigScreen(repo = repo(), onBack = {}) }
        rule.onNodeWithText("services:", substring = true).assertIsDisplayed()
    }

    @Test fun saveButtonIsVisible() {
        rule.setContent { ConfigScreen(repo = repo(), onBack = {}) }
        rule.onNodeWithText("保存").assertIsDisplayed()
    }

    @Test fun validateButtonIsVisible() {
        rule.setContent { ConfigScreen(repo = repo(), onBack = {}) }
        rule.onNodeWithText("验证").assertIsDisplayed()
    }
}
