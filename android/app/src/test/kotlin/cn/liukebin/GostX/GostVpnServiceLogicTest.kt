package cn.liukebin.gostx

import cn.liukebin.gostx.data.VpnStatus
import cn.liukebin.gostx.service.shouldAcceptStartAction
import cn.liukebin.gostx.service.shouldProceedWithRestart
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

    // ── shouldProceedWithRestart ─────────────────────────────────────────
    // After stopVpn(updatePersistentState=false) — the network-restart
    // half-stop — the state is set to CONNECTING. If a manual user stop ran
    // concurrently and changed the state to STOPPED (or ERROR / STOPPING),
    // the restart must abort.

    @Test
    fun `restart proceeds when state is still connecting`() {
        assertTrue(shouldProceedWithRestart(VpnStatus.CONNECTING))
    }

    @Test
    fun `restart aborts when state changed to stopped`() {
        assertFalse(shouldProceedWithRestart(VpnStatus.STOPPED))
    }

    @Test
    fun `restart aborts when state changed to error`() {
        assertFalse(shouldProceedWithRestart(VpnStatus.ERROR))
    }

    @Test
    fun `restart aborts when state changed to stopping`() {
        assertFalse(shouldProceedWithRestart(VpnStatus.STOPPING))
    }
}
