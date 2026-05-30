package cn.liukebin.GostX.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.liukebin.GostX.data.ConfigRepository
import cn.liukebin.GostX.data.GlobalVpnState
import cn.liukebin.GostX.data.VpnStatus
import cn.liukebin.GostX.service.GostLibBridge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ConfigUiState(
    val profileName: String = "",
    val yaml: String = "",
    val validationError: String? = null,
    val isSaved: Boolean = false,
    val canDelete: Boolean = false
)

class ConfigViewModel(
    private val repo: ConfigRepository,
    private val profileId: String
) : ViewModel() {

    private val _ui = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _ui.asStateFlow()

    private val _navBack = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navBack: SharedFlow<Unit> = _navBack.asSharedFlow()

    init {
        load()
        viewModelScope.launch {
            GlobalVpnState.state.collect { vpnState ->
                _ui.value = _ui.value.copy(canDelete = computeCanDelete(vpnState.status))
            }
        }
    }

    private fun computeCanDelete(status: VpnStatus): Boolean =
        repo.getProfiles().size > 1 && (status == VpnStatus.STOPPED || status == VpnStatus.ERROR)

    private fun load() {
        val profiles = repo.getProfiles()
        val name = profiles.find { it.id == profileId }?.name ?: profileId
        val status = GlobalVpnState.state.value.status
        _ui.value = ConfigUiState(
            profileName = name,
            yaml = repo.getConfig(profileId),
            canDelete = computeCanDelete(status)
        )
    }

    fun onYamlChange(yaml: String) {
        _ui.value = _ui.value.copy(yaml = yaml, validationError = null, isSaved = false)
    }

    fun save() {
        val error = GostLibBridge.validateConfig(_ui.value.yaml)
        if (error.isNotEmpty()) {
            _ui.value = _ui.value.copy(validationError = error)
            return
        }
        repo.saveConfig(profileId, _ui.value.yaml)
        _ui.value = _ui.value.copy(isSaved = true, validationError = null)
    }

    fun clearValidationError() {
        _ui.value = _ui.value.copy(validationError = null)
    }

    fun deleteProfile() {
        if (!_ui.value.canDelete) return
        repo.deleteProfile(profileId)
        _navBack.tryEmit(Unit)
    }
}
