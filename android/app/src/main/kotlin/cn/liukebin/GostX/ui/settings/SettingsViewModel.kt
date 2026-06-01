package cn.liukebin.GostX.ui.settings

import androidx.lifecycle.ViewModel
import cn.liukebin.GostX.data.ConfigRepository
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(private val repo: ConfigRepository) : ViewModel() {
    val loggingEnabled: StateFlow<Boolean> = repo.loggingEnabledFlow

    fun setLoggingEnabled(value: Boolean) {
        repo.loggingEnabled = value
    }
}
