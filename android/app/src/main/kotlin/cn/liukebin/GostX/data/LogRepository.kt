package cn.liukebin.GostX.data

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogRepository {
    @Volatile private var logFile: File? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

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
                val ts = timeFormat.format(Date())
                FileWriter(file, /* append = */ true).use { it.write("$ts $line\n") }
            }
        } catch (_: Exception) {
            // Silently ignore — logging must not affect VPN operation
        }
    }

    /** Truncates the log file to zero bytes. Keeps the file on disk so that
     *  any open file descriptors (e.g. Go's drain goroutine) remain valid;
     *  O_APPEND writers will resume from offset 0 after truncation. */
    fun deleteLog() {
        try {
            synchronized(this) {
                logFile?.writeText("")
            }
        } catch (_: Exception) {
            // Ignore — delete failure must not affect VPN operation
        }
    }
}
