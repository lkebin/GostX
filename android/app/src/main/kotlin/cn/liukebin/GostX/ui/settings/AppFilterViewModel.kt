package cn.liukebin.gostx.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.liukebin.gostx.data.AppFilterMode
import cn.liukebin.gostx.data.ConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InstalledApp(
    val packageName: String,
    val label: String,
    val hasLauncher: Boolean = true
)

data class AppFilterUiState(
    val isLoading: Boolean = true,
    val apps: List<InstalledApp> = emptyList(),
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val isWhitelistMode: Boolean = false,
    val showAll: Boolean = false
) {
    val filtered: List<InstalledApp>
        get() {
            val visible = if (showAll) apps else apps.filter { it.hasLauncher }
            return if (query.isBlank()) visible
                   else visible.filter { it.label.contains(query, ignoreCase = true) }
        }

    val canSave: Boolean
        get() = !isWhitelistMode || selected.isNotEmpty()
}

class AppFilterViewModel(
    private val repo: ConfigRepository,
    private val appLoader: suspend () -> List<InstalledApp>
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AppFilterUiState(
            selected = repo.appFilterList,
            isWhitelistMode = repo.appFilterMode == AppFilterMode.WHITELIST
        )
    )
    val uiState: StateFlow<AppFilterUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val apps = runCatching { appLoader() }.getOrDefault(emptyList())
            _uiState.update { it.copy(isLoading = false, apps = apps) }
        }
    }

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun toggleApp(packageName: String) {
        _uiState.update { state ->
            val newSelected = if (packageName in state.selected)
                state.selected - packageName
            else
                state.selected + packageName
            state.copy(selected = newSelected)
        }
    }

    fun toggleShowAll() {
        _uiState.update { it.copy(showAll = !it.showAll) }
    }

    fun save() {
        val state = _uiState.value
        if (!state.canSave) return
        repo.appFilterList = state.selected
    }
}
