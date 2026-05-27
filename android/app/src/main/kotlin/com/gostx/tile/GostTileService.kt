package com.gostx.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.gostx.data.GlobalVpnState
import com.gostx.data.VpnStatus
import com.gostx.service.GostVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
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
                    state = when (vpnState.status) {
                        VpnStatus.CONNECTED -> Tile.STATE_ACTIVE
                        VpnStatus.CONNECTING -> Tile.STATE_ACTIVE
                        else -> Tile.STATE_INACTIVE
                    }
                    updateTile()
                }
            }
        }
    }

    override fun onClick() {
        super.onClick()
        val status = GlobalVpnState.state.value.status
        if (status == VpnStatus.CONNECTED || status == VpnStatus.CONNECTING) {
            GostVpnService.stop(applicationContext)
        } else {
            GostVpnService.start(applicationContext)
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
