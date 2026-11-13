package cn.liukebin.gostx

import cn.liukebin.gostx.data.VpnStatus
import cn.liukebin.gostx.service.shouldRestartVpnOnUsableNetwork
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GostVpnServicePolicyTest {
    @Test fun `connected vpn restarts when a usable non-vpn network appears`() {
        assertTrue(
            shouldRestartVpnOnUsableNetwork(
                VpnStatus.CONNECTED,
                isVpnNetwork = false,
                isUsableNetwork = true,
                restartInProgress = false
            )
        )
    }

    @Test fun `vpn network change does not restart service`() {
        assertFalse(
            shouldRestartVpnOnUsableNetwork(
                VpnStatus.CONNECTED,
                isVpnNetwork = true,
                isUsableNetwork = true,
                restartInProgress = false
            )
        )
    }

    // A network that is merely "available" (no IP yet, no upstream, captive
    // portal) must NOT trigger a rebuild: the tunnel would come up connected
    // against a network that cannot carry traffic, and every dial would fail
    // with "no route to host".
    @Test fun `unvalidated network availability does not restart service`() {
        assertFalse(
            shouldRestartVpnOnUsableNetwork(
                VpnStatus.CONNECTED,
                isVpnNetwork = false,
                isUsableNetwork = false,
                restartInProgress = false
            )
        )
    }

    @Test fun `restart is skipped while reconnect already running`() {
        assertFalse(
            shouldRestartVpnOnUsableNetwork(
                VpnStatus.CONNECTED,
                isVpnNetwork = false,
                isUsableNetwork = true,
                restartInProgress = true
            )
        )
    }

    @Test fun `stopped vpn ignores network changes`() {
        assertFalse(
            shouldRestartVpnOnUsableNetwork(
                VpnStatus.STOPPED,
                isVpnNetwork = false,
                isUsableNetwork = true,
                restartInProgress = false
            )
        )
    }
}