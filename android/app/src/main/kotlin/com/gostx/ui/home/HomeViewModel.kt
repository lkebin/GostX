package com.gostx.ui.home

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gostx.data.GlobalVpnState
import com.gostx.data.VpnStatus
import com.gostx.service.GostVpnService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    val vpnState = GlobalVpnState.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, GlobalVpnState.state.value)

    fun toggleVpn() {
        val ctx = getApplication<Application>()
        val action = if (vpnState.value.status == VpnStatus.CONNECTED ||
            vpnState.value.status == VpnStatus.CONNECTING
        ) GostVpnService.ACTION_STOP else GostVpnService.ACTION_START

        val intent = Intent(ctx, GostVpnService::class.java).apply { this.action = action }
        if (action == GostVpnService.ACTION_START) ctx.startForegroundService(intent)
        else ctx.startService(intent)
    }
}
