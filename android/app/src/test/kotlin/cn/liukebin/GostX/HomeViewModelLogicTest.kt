package cn.liukebin.gostx

import cn.liukebin.gostx.data.VpnStatus
import cn.liukebin.gostx.ui.home.VpnToggleAction
import cn.liukebin.gostx.ui.home.canSetActiveProfile
import cn.liukebin.gostx.ui.home.resolveVpnToggleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelLogicTest {
    @Test fun `stopped vpn without permission requests permission first`() {
        assertEquals(
            VpnToggleAction.REQUEST_PERMISSION,
            resolveVpnToggleAction(VpnStatus.STOPPED, hasVpnPermission = false)
        )
    }

    @Test fun `stopped vpn with permission starts service`() {
        assertEquals(
            VpnToggleAction.START,
            resolveVpnToggleAction(VpnStatus.STOPPED, hasVpnPermission = true)
        )
    }

    @Test fun `connected vpn stops service`() {
        assertEquals(
            VpnToggleAction.STOP,
            resolveVpnToggleAction(VpnStatus.CONNECTED, hasVpnPermission = true)
        )
    }

    @Test fun `stopping vpn resolves to stop action`() {
        assertEquals(
            VpnToggleAction.STOP,
            resolveVpnToggleAction(VpnStatus.STOPPING, hasVpnPermission = true)
        )
    }

    @Test fun `error vpn with permission starts service`() {
        assertEquals(
            VpnToggleAction.START,
            resolveVpnToggleAction(VpnStatus.ERROR, hasVpnPermission = true)
        )
    }

    @Test fun `can set active profile when stopped`() {
        assertTrue(canSetActiveProfile(VpnStatus.STOPPED))
    }

    @Test fun `can set active profile when error`() {
        assertTrue(canSetActiveProfile(VpnStatus.ERROR))
    }

    @Test fun `cannot set active profile when connecting`() {
        assertFalse(canSetActiveProfile(VpnStatus.CONNECTING))
    }

    @Test fun `cannot set active profile when connected`() {
        assertFalse(canSetActiveProfile(VpnStatus.CONNECTED))
    }

    @Test fun `cannot set active profile when stopping`() {
        assertFalse(canSetActiveProfile(VpnStatus.STOPPING))
    }
}
