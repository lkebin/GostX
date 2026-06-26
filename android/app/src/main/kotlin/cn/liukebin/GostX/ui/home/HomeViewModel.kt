package cn.liukebin.gostx.ui.home

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.liukebin.gostx.data.ConfigProfile
import cn.liukebin.gostx.data.ConfigRepository
import cn.liukebin.gostx.data.GlobalVpnState
import cn.liukebin.gostx.data.VpnStatus
import cn.liukebin.gostx.service.GostVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class VpnToggleAction { START, REQUEST_PERMISSION, STOP }

internal fun resolveVpnToggleAction(status: VpnStatus, hasVpnPermission: Boolean): VpnToggleAction = when {
    status == VpnStatus.CONNECTED || status == VpnStatus.CONNECTING || status == VpnStatus.STOPPING -> VpnToggleAction.STOP
    hasVpnPermission -> VpnToggleAction.START
    else -> VpnToggleAction.REQUEST_PERMISSION
}

internal fun canSetActiveProfile(status: VpnStatus): Boolean =
    status == VpnStatus.STOPPED || status == VpnStatus.ERROR

data class HomeUiState(
    val profiles: List<ConfigProfile> = emptyList(),
    val activeProfileId: String = ""
)

class HomeViewModel(
    app: Application,
    private val repo: ConfigRepository
) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE)

    val vpnState = GlobalVpnState.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, GlobalVpnState.state.value)

    val loggingEnabled: StateFlow<Boolean> = repo.loggingEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, repo.loggingEnabled)

    private val _batteryOptimizationNeeded = MutableStateFlow(false)
    val batteryOptimizationNeeded: StateFlow<Boolean> = _batteryOptimizationNeeded

    private val _showVpnDisclosureDialog = MutableStateFlow(false)
    val showVpnDisclosureDialog: StateFlow<Boolean> = _showVpnDisclosureDialog

    private val vpnDisclosureAccepted: Boolean
        get() = prefs.getBoolean("vpn_disclosure_accepted", false)

    init {
        checkBatteryOptimization()
    }

    /** Re-checks battery optimization status; call on every screen resume. */
    fun checkBatteryOptimization() {
        val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val dismissed = prefs.getBoolean("battery_opt_dismissed", false)
        _batteryOptimizationNeeded.value = !dismissed && !pm.isIgnoringBatteryOptimizations(getApplication<Application>().packageName)
    }

    fun dismissBatteryOptimizationPrompt() {
        prefs.edit().putBoolean("battery_opt_dismissed", true).apply()
        _batteryOptimizationNeeded.value = false
    }

    fun openBatteryOptimizationSettings() {
        val ctx = getApplication<Application>()
        ctx.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${ctx.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    }

    private val refreshTrigger = MutableStateFlow(0)

    fun refresh() {
        refreshTrigger.value++
    }

    val homeState: StateFlow<HomeUiState> = combine(
        refreshTrigger,
        repo.profilesFlow,
        repo.activeProfileIdFlow
    ) { _, profiles, activeId -> HomeUiState(profiles = profiles, activeProfileId = activeId) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            HomeUiState(profiles = repo.profilesFlow.value, activeProfileId = repo.activeProfileIdFlow.value)
        )

    fun setActiveProfile(profileId: String) {
        if (!canSetActiveProfile(vpnState.value.status)) return
        repo.setActiveProfile(profileId)
    }

    /** Returns false if the name is already taken. */
    fun addProfile(name: String): String? = repo.addProfile(name)

    fun toggleVpn(onVpnPermissionRequired: () -> Unit = {}) {
        val ctx = getApplication<Application>()
        when (resolveVpnToggleAction(vpnState.value.status, VpnService.prepare(ctx) == null)) {
            VpnToggleAction.STOP -> {
                GlobalVpnState.setStopping()
                startService(ctx, GostVpnService.ACTION_STOP)
            }
            VpnToggleAction.START -> {
                if (vpnDisclosureAccepted) {
                    startService(ctx, GostVpnService.ACTION_START)
                } else {
                    _showVpnDisclosureDialog.value = true
                }
            }
            VpnToggleAction.REQUEST_PERMISSION -> onVpnPermissionRequired()
        }
    }

    fun acceptVpnDisclosure() {
        if (vpnDisclosureAccepted) return
        prefs.edit().putBoolean("vpn_disclosure_accepted", true).apply()
        _showVpnDisclosureDialog.value = false
        val ctx = getApplication<Application>()
        startService(ctx, GostVpnService.ACTION_START)
    }

    fun dismissVpnDisclosure() {
        _showVpnDisclosureDialog.value = false
    }

    private fun startService(ctx: Application, action: String) {
        val intent = Intent(ctx, GostVpnService::class.java).apply { this.action = action }
        if (action == GostVpnService.ACTION_START) ctx.startForegroundService(intent)
        else ctx.startService(intent)
    }
}
