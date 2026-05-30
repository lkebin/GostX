package cn.liukebin.GostX.ui.config

import androidx.lifecycle.ViewModel
import cn.liukebin.GostX.service.GostLibBridge
import cn.liukebin.GostX.data.ConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConfigUiState(
    val yaml: String = "",
    val profiles: List<String> = emptyList(),
    val activeProfileId: String = "default",
    val validationError: String? = null,
    val isSaved: Boolean = false
)

class ConfigViewModel(private val repo: ConfigRepository) : ViewModel() {
    private val _ui = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _ui.asStateFlow()

    init {
        load()
    }

    private fun load() {
        val id = repo.getActiveProfileId()
        _ui.value = ConfigUiState(
            yaml = repo.getConfig(id),
            profiles = repo.getProfiles().map { it.id },
            activeProfileId = id
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
        repo.saveConfig(_ui.value.activeProfileId, _ui.value.yaml)
        _ui.value = _ui.value.copy(isSaved = true, validationError = null)
    }

    fun clearValidationError() {
        _ui.value = _ui.value.copy(validationError = null)
    }

    fun switchProfile(profileId: String) {
        repo.setActiveProfile(profileId)
        _ui.value = _ui.value.copy(
            activeProfileId = profileId,
            yaml = repo.getConfig(profileId),
            validationError = null
        )
    }
}
