package cn.liukebin.GostX

import cn.liukebin.GostX.data.VpnStatus
import cn.liukebin.GostX.service.shouldRestartVpnOnNetworkAvailable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GostVpnServicePolicyTest {
    @Test fun `connected vpn restarts when non-vpn network becomes available`() {
        assertTrue(shouldRestartVpnOnNetworkAvailable(VpnStatus.CONNECTED, isVpnNetwork = false, restartInProgress = false))
    }

    @Test fun `vpn network availability does not restart service`() {
        assertFalse(shouldRestartVpnOnNetworkAvailable(VpnStatus.CONNECTED, isVpnNetwork = true, restartInProgress = false))
    }

    @Test fun `restart is skipped while reconnect already running`() {
        assertFalse(shouldRestartVpnOnNetworkAvailable(VpnStatus.CONNECTED, isVpnNetwork = false, restartInProgress = true))
    }

    @Test fun `stopped vpn ignores network availability`() {
        assertFalse(shouldRestartVpnOnNetworkAvailable(VpnStatus.STOPPED, isVpnNetwork = false, restartInProgress = false))
    }
}
