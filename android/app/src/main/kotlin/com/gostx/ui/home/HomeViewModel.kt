package com.gostx.ui.home

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gostx.data.GlobalVpnState
import com.gostx.data.VpnStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

private const val ACTION_START = "com.gostx.START_VPN"
private const val ACTION_STOP = "com.gostx.STOP_VPN"
private const val SERVICE_CLASS = "com.gostx.service.GostVpnService"

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    val vpnState = GlobalVpnState.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, GlobalVpnState.state.value)

    fun toggleVpn() {
        val ctx = getApplication<Application>()
        val action = if (vpnState.value.status == VpnStatus.CONNECTED ||
            vpnState.value.status == VpnStatus.CONNECTING
        ) ACTION_STOP else ACTION_START

        val intent = Intent(action).setClassName(ctx, SERVICE_CLASS)
        if (action == ACTION_START) ctx.startForegroundService(intent)
        else ctx.startService(intent)
    }
}
