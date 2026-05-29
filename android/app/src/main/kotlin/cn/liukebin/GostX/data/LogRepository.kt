package cn.liukebin.GostX.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object LogRepository {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun append(line: String) {
        val updated = (_logs.value + line).takeLast(1000)
        _logs.value = updated
    }

    fun clear() { _logs.value = emptyList() }
}
