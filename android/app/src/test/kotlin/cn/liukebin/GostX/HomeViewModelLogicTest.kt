package cn.liukebin.GostX

import cn.liukebin.GostX.data.VpnStatus
import cn.liukebin.GostX.ui.home.VpnToggleAction
import cn.liukebin.GostX.ui.home.resolveVpnToggleAction
import org.junit.Assert.assertEquals
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
}
