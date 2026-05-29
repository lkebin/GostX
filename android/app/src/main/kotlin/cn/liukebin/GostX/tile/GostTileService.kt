package cn.liukebin.GostX.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import cn.liukebin.GostX.R
import cn.liukebin.GostX.data.GlobalVpnState
import cn.liukebin.GostX.data.VpnStatus
import cn.liukebin.GostX.service.GostVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GostTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listeningJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listeningJob?.cancel()
        listeningJob = scope.launch {
            GlobalVpnState.state.collect { vpnState ->
                qsTile?.apply {
                    when (vpnState.status) {
                        VpnStatus.CONNECTED -> {
                            state = Tile.STATE_ACTIVE
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = getString(R.string.tile_connected)
                        }
                        VpnStatus.CONNECTING -> {
                            state = Tile.STATE_UNAVAILABLE
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = getString(R.string.tile_connecting)
                        }
                        VpnStatus.ERROR -> {
                            state = Tile.STATE_INACTIVE
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = getString(R.string.tile_error)
                        }
                        else -> {
                            state = Tile.STATE_INACTIVE
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = null
                        }
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
            VpnStatus.STOPPED -> GostVpnService.start(applicationContext)
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
