package cn.liukebin.GostX

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import cn.liukebin.GostX.data.GlobalVpnState
import cn.liukebin.GostX.data.VpnState
import cn.liukebin.GostX.data.VpnStatus
import cn.liukebin.GostX.ui.home.HomeScreen
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun showsStartButtonWhenStopped() {
        GlobalVpnState.setState(VpnState(VpnStatus.STOPPED))
        rule.setContent { HomeScreen() }
        rule.onNodeWithText("启动 VPN").assertIsDisplayed()
    }

    @Test fun showsStopButtonWhenConnected() {
        GlobalVpnState.setState(VpnState(VpnStatus.CONNECTED, "127.0.0.1:10808"))
        rule.setContent { HomeScreen() }
        rule.onNodeWithText("停止 VPN").assertIsDisplayed()
    }

    @Test fun showsListenAddrWhenConnected() {
        GlobalVpnState.setState(VpnState(VpnStatus.CONNECTED, "127.0.0.1:10808"))
        rule.setContent { HomeScreen() }
        rule.onNodeWithText("监听: 127.0.0.1:10808").assertIsDisplayed()
    }
}
