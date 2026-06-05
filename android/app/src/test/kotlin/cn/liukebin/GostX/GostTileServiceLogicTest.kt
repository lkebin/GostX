package cn.liukebin.gostx

import android.os.Build
import android.service.quicksettings.Tile
import cn.liukebin.gostx.R
import cn.liukebin.gostx.data.VpnStatus
import cn.liukebin.gostx.tile.canSetTileSubtitle
import cn.liukebin.gostx.tile.resolveTileState
import cn.liukebin.gostx.tile.resolveTileSubtitleRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GostTileServiceLogicTest {
    @Test fun `stopping vpn makes tile unavailable`() {
        assertEquals(Tile.STATE_UNAVAILABLE, resolveTileState(VpnStatus.STOPPING))
    }

    @Test fun `stopping vpn uses connecting subtitle`() {
        assertEquals(
            R.string.tile_connecting,
            resolveTileSubtitleRes(VpnStatus.STOPPING)
        )
    }

    @Test fun `stopped vpn has no subtitle`() {
        assertNull(resolveTileSubtitleRes(VpnStatus.STOPPED))
    }

    @Test fun `tile subtitle setter is gated below q`() {
        assertEquals(false, canSetTileSubtitle(Build.VERSION_CODES.P))
        assertEquals(true, canSetTileSubtitle(Build.VERSION_CODES.Q))
    }
}
