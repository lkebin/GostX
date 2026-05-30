package cn.liukebin.GostX.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter

object LogRepository {
    @Volatile private var logFile: File? = null
    
    // Deprecated stubs for backward compatibility - removed in later tasks
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    @Deprecated("Use file-based logging instead")
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun init(context: Context) {
        logFile = File(context.filesDir, "gostx.log")
    }

    /** For tests only — bypasses Android Context. */
    internal fun initForTest(file: File) {
        logFile = file
    }

    fun getLogFile(): File =
        requireNotNull(logFile) { "LogRepository.init() must be called before use" }

    fun append(line: String) {
        val file = logFile ?: return
        try {
            synchronized(this) {
                FileWriter(file, /* append = */ true).use { it.write("$line\n") }
            }
        } catch (_: Exception) {
            // Silently ignore — logging must not affect VPN operation
        }
    }

    fun deleteLog() {
        try {
            synchronized(this) {
                logFile?.delete()
            }
        } catch (_: Exception) {
            // Ignore — delete failure must not affect VPN operation
        }
    }
    
    @Deprecated("Use deleteLog() instead")
    fun clear() {
        deleteLog()
    }
}
