package cn.liukebin.gostx

import cn.liukebin.gostx.data.VpnStatus
import cn.liukebin.gostx.service.shouldAcceptStartAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GostVpnServiceLogicTest {

    // ── shouldAcceptStartAction ──────────────────────────────────────────
    // Guards against double-start: a second ACTION_START while already
    // CONNECTED/CONNECTING must be rejected to prevent overlapping startVpn()
    // coroutines.

    @Test
    fun `accepts start when stopped`() {
        assertTrue(shouldAcceptStartAction(VpnStatus.STOPPED))
    }

    @Test
    fun `accepts start when error`() {
        assertTrue(shouldAcceptStartAction(VpnStatus.ERROR))
    }

    @Test
    fun `accepts start when stopping`() {
        assertTrue(shouldAcceptStartAction(VpnStatus.STOPPING))
    }

    @Test
    fun `rejects start when already connecting`() {
        assertFalse(shouldAcceptStartAction(VpnStatus.CONNECTING))
    }

    @Test
    fun `rejects start when already connected`() {
        assertFalse(shouldAcceptStartAction(VpnStatus.CONNECTED))
    }
}
