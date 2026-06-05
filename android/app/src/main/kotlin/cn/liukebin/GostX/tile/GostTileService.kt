package cn.liukebin.gostx.tile

import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import cn.liukebin.gostx.R
import cn.liukebin.gostx.data.GlobalVpnState
import cn.liukebin.gostx.data.VpnStatus
import cn.liukebin.gostx.service.GostVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal fun resolveTileState(status: VpnStatus): Int = when (status) {
    VpnStatus.CONNECTED -> Tile.STATE_ACTIVE
    VpnStatus.CONNECTING, VpnStatus.STOPPING -> Tile.STATE_UNAVAILABLE
    VpnStatus.ERROR, VpnStatus.STOPPED -> Tile.STATE_INACTIVE
}

internal fun canSetTileSubtitle(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.Q

internal fun resolveTileSubtitleRes(status: VpnStatus): Int? = when (status) {
    VpnStatus.CONNECTED -> R.string.tile_connected
    VpnStatus.CONNECTING, VpnStatus.STOPPING -> R.string.tile_connecting
    VpnStatus.ERROR -> R.string.tile_error
    VpnStatus.STOPPED -> null
}

class GostTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listeningJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listeningJob?.cancel()
        listeningJob = scope.launch {
            GlobalVpnState.state.collect { vpnState ->
                qsTile?.apply {
                    state = resolveTileState(vpnState.status)
                    if (canSetTileSubtitle(Build.VERSION.SDK_INT)) {
                        subtitle = resolveTileSubtitleRes(vpnState.status)?.let(::getString)
                    }
                    updateTile()
                }
            }
        }
    }

    override fun onClick() {
        super.onClick()
        when (GlobalVpnState.state.value.status) {
            VpnStatus.CONNECTED -> GostVpnService.stop(applicationContext)
            VpnStatus.STOPPED -> {
                val intent = VpnService.prepare(applicationContext)
                if (intent != null) {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                } else {
                    GostVpnService.start(applicationContext)
                }
            }
            else -> {}
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
