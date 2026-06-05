package cn.liukebin.gostx.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnStatus { STOPPED, CONNECTING, CONNECTED, STOPPING, ERROR }

data class VpnState(
    val status: VpnStatus = VpnStatus.STOPPED,
    val listenAddr: String = "",
    val error: String? = null
)

open class VpnStateRepository {
    private val _state = MutableStateFlow(VpnState())
    val state: StateFlow<VpnState> = _state.asStateFlow()

    fun setState(s: VpnState) { _state.value = s }
    fun setConnecting() = setState(VpnState(VpnStatus.CONNECTING))
    fun setConnected(addr: String) = setState(VpnState(VpnStatus.CONNECTED, addr))
    fun setStopping() = setState(VpnState(VpnStatus.STOPPING))
    fun setStopped() = setState(VpnState(VpnStatus.STOPPED))
    fun setError(msg: String) = setState(VpnState(VpnStatus.ERROR, error = msg))
}

object GlobalVpnState : VpnStateRepository()
