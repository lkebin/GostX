package cn.liukebin.GostX

import android.os.Build
import android.service.quicksettings.Tile
import cn.liukebin.GostX.R
import cn.liukebin.GostX.data.VpnStatus
import cn.liukebin.GostX.tile.canSetTileSubtitle
import cn.liukebin.GostX.tile.resolveTileState
import cn.liukebin.GostX.tile.resolveTileSubtitleRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GostTileServiceLogicTest {
    @Test fun `stopping vpn makes tile unavailable`() {
        assertEquals(Tile.STATE_UNAVAILABLE, resolveTileState(VpnStatus.STOPPING))
    }

    @Test fun `stopping vpn uses connecting subtitle on q and above`() {
        assertEquals(
            R.string.tile_connecting,
            resolveTileSubtitleRes(VpnStatus.STOPPING, Build.VERSION_CODES.Q)
        )
    }

    @Test fun `stopping vpn has no subtitle below q`() {
        assertNull(resolveTileSubtitleRes(VpnStatus.STOPPING, Build.VERSION_CODES.P))
    }

    @Test fun `tile subtitle setter is gated below q`() {
        assertEquals(false, canSetTileSubtitle(Build.VERSION_CODES.P))
        assertEquals(true, canSetTileSubtitle(Build.VERSION_CODES.Q))
    }
}
