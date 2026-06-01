package cn.liukebin.GostX.ui.settings

import androidx.lifecycle.ViewModel
import cn.liukebin.GostX.data.AppFilterMode
import cn.liukebin.GostX.data.ConfigRepository
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(private val repo: ConfigRepository) : ViewModel() {
    val loggingEnabled: StateFlow<Boolean> = repo.loggingEnabledFlow
    val appFilterMode: StateFlow<AppFilterMode> = repo.appFilterModeFlow
    val appFilterList: StateFlow<Set<String>> = repo.appFilterListFlow

    fun setLoggingEnabled(value: Boolean) {
        repo.loggingEnabled = value
    }

    fun setAppFilterMode(mode: AppFilterMode) {
        repo.appFilterMode = mode
    }
}
