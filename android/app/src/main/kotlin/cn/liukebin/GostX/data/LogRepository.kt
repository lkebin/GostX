package cn.liukebin.GostX.data

import android.content.Context
import java.io.File
import java.io.FileWriter

object LogRepository {
    @Volatile private var logFile: File? = null

    fun init(context: Context) {
        if (logFile != null) return
        synchronized(this) {
            if (logFile != null) return
            logFile = File(context.filesDir, "gostx.log")
        }
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
}
