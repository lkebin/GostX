package cn.liukebin.gostx.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.liukebin.gostx.data.AppFilterMode
import cn.liukebin.gostx.data.ConfigRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val EMPTY_RESULT_RETRIES = 3
private const val RETRY_DELAY_MS = 500L

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
            val q = query.trim()
            return if (q.isEmpty()) visible
                   else visible.filter {
                       it.label.contains(q, ignoreCase = true) ||
                           it.packageName.contains(q, ignoreCase = true)
                   }
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

    private var loadJob: Job? = null

    init {
        refresh()
    }

    /**
     * (Re)loads the installed app list.
     *
     * ROMs that gate the app list behind a runtime prompt ("read installed
     * apps") answer the query that triggers the prompt with an empty list. The
     * screen calls this again on resume, so the list fills in as soon as the
     * user grants the permission, and a few retries cover prompts that do not
     * pause the activity.
     */
    fun refresh() {
        if (loadJob?.isActive == true) return
        _uiState.update { it.copy(isLoading = it.apps.isEmpty()) }
        loadJob = viewModelScope.launch {
            var apps = loadApps()
            var attempt = 0
            while (apps.isEmpty() && attempt < EMPTY_RESULT_RETRIES) {
                attempt++
                delay(RETRY_DELAY_MS)
                apps = loadApps()
            }
            _uiState.update { it.copy(isLoading = false, apps = apps) }
        }
    }

    private suspend fun loadApps(): List<InstalledApp> =
        runCatching { appLoader() }.getOrDefault(emptyList())

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
