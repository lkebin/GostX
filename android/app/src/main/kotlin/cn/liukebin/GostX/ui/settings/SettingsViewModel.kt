package cn.liukebin.gostx.ui.settings

import androidx.lifecycle.ViewModel
import cn.liukebin.gostx.data.AppFilterMode
import cn.liukebin.gostx.data.ConfigRepository
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(private val repo: ConfigRepository) : ViewModel() {
    val loggingEnabled: StateFlow<Boolean> = repo.loggingEnabledFlow
    val logLevel: StateFlow<String> = repo.logLevelFlow
    val appFilterEnabled: StateFlow<Boolean> = repo.appFilterEnabledFlow
    val appFilterMode: StateFlow<AppFilterMode> = repo.appFilterModeFlow
    val appFilterList: StateFlow<Set<String>> = repo.appFilterListFlow

    fun setLoggingEnabled(value: Boolean) {
        repo.loggingEnabled = value
    }

    fun setLogLevel(level: String) {
        repo.logLevel = level
    }

    fun setAppFilterEnabled(value: Boolean) {
        repo.appFilterEnabled = value
    }

    fun setAppFilterMode(mode: AppFilterMode) {
        repo.appFilterMode = mode
    }
}
