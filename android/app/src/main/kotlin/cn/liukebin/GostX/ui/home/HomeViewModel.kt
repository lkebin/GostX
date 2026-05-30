package cn.liukebin.GostX.ui.home

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.liukebin.GostX.data.GlobalVpnState
import cn.liukebin.GostX.data.VpnStatus
import cn.liukebin.GostX.service.GostVpnService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

enum class VpnToggleAction { START, REQUEST_PERMISSION, STOP }

internal fun resolveVpnToggleAction(status: VpnStatus, hasVpnPermission: Boolean): VpnToggleAction = when {
    status == VpnStatus.CONNECTED || status == VpnStatus.CONNECTING || status == VpnStatus.STOPPING -> VpnToggleAction.STOP
    hasVpnPermission -> VpnToggleAction.START
    else -> VpnToggleAction.REQUEST_PERMISSION
}

internal fun canSetActiveProfile(status: VpnStatus): Boolean =
    status == VpnStatus.STOPPED || status == VpnStatus.ERROR

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    val vpnState = GlobalVpnState.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, GlobalVpnState.state.value)

    fun toggleVpn(onVpnPermissionRequired: () -> Unit = {}) {
        val ctx = getApplication<Application>()
        when (resolveVpnToggleAction(vpnState.value.status, VpnService.prepare(ctx) == null)) {
            VpnToggleAction.STOP -> startService(ctx, GostVpnService.ACTION_STOP)
            VpnToggleAction.START -> startService(ctx, GostVpnService.ACTION_START)
            VpnToggleAction.REQUEST_PERMISSION -> onVpnPermissionRequired()
        }
    }

    private fun startService(ctx: Application, action: String) {
        val intent = Intent(ctx, GostVpnService::class.java).apply { this.action = action }
        if (action == GostVpnService.ACTION_START) ctx.startForegroundService(intent)
        else ctx.startService(intent)
    }
}
