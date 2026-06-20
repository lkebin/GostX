package cn.liukebin.gostx.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.liukebin.gostx.data.ConfigRepository
import cn.liukebin.gostx.data.GlobalVpnState
import cn.liukebin.gostx.data.VpnStatus
import cn.liukebin.gostx.service.LibgostBridge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ConfigUiState(
    val profileName: String = "",
    val yaml: String = "",
    val validationError: String? = null,
    val isSaved: Boolean = false,
    val canDelete: Boolean = false,
    val otherProfileNames: Set<String> = emptySet()
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
            combine(GlobalVpnState.state, repo.profilesFlow) { vpnState, profiles ->
                val canDelete = profiles.any { it.id == profileId } && (vpnState.status == VpnStatus.STOPPED || vpnState.status == VpnStatus.ERROR)
                val otherNames = profiles.filter { it.id != profileId }.map { it.name }.toSet()
                canDelete to otherNames
            }.collect { (canDelete, otherNames) ->
                _ui.value = _ui.value.copy(canDelete = canDelete, otherProfileNames = otherNames)
            }
        }
    }

    private fun load() {
        val profiles = repo.getProfiles()
        val name = profiles.find { it.id == profileId }?.name ?: profileId
        _ui.value = ConfigUiState(
            profileName = name,
            yaml = repo.getConfig(profileId)
        )
    }

    fun onYamlChange(yaml: String) {
        _ui.value = _ui.value.copy(yaml = yaml, validationError = null, isSaved = false)
    }

    fun save() {
        val error = LibgostBridge.validateConfig(_ui.value.yaml)
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

    fun renameProfile(newName: String) {
        val trimmed = newName.trim()
        if (repo.renameProfile(profileId, trimmed)) {
            _ui.value = _ui.value.copy(profileName = trimmed)
        }
    }

    fun deleteProfile() {
        if (!_ui.value.canDelete) return
        repo.deleteProfile(profileId)
        _navBack.tryEmit(Unit)
    }
}
