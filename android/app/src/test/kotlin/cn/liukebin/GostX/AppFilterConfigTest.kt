package cn.liukebin.gostx

import cn.liukebin.gostx.data.AppFilterMode
import cn.liukebin.gostx.service.AppFilterConfig
import cn.liukebin.gostx.service.buildAppFilterConfig
import org.junit.Assert.*
import org.junit.Test

class AppFilterConfigTest {
    private val SELF = "cn.liukebin.gostx"

    @Test fun `blacklist disallows user packages and self`() {
        val cfg = buildAppFilterConfig(AppFilterMode.BLACKLIST, setOf("com.a", "com.b"), SELF)
        assertEquals(setOf("com.a", "com.b", SELF), cfg.disallowed)
        assertTrue(cfg.allowed.isEmpty())
    }

    @Test fun `blacklist with empty filter list only disallows self`() {
        val cfg = buildAppFilterConfig(AppFilterMode.BLACKLIST, emptySet(), SELF)
        assertEquals(setOf(SELF), cfg.disallowed)
        assertTrue(cfg.allowed.isEmpty())
    }

    @Test fun `whitelist allows user packages`() {
        val cfg = buildAppFilterConfig(AppFilterMode.WHITELIST, setOf("com.a", "com.b"), SELF)
        assertTrue(cfg.disallowed.isEmpty())
        assertEquals(setOf("com.a", "com.b"), cfg.allowed)
    }

    @Test fun `whitelist excludes self even if present in filter list`() {
        val cfg = buildAppFilterConfig(AppFilterMode.WHITELIST, setOf("com.a", SELF), SELF)
        assertFalse(cfg.allowed.contains(SELF))
        assertTrue(cfg.disallowed.isEmpty())
    }

    @Test fun `whitelist with empty filter list produces empty allowed`() {
        val cfg = buildAppFilterConfig(AppFilterMode.WHITELIST, emptySet(), SELF)
        assertTrue(cfg.disallowed.isEmpty())
        assertTrue(cfg.allowed.isEmpty())
    }
}
