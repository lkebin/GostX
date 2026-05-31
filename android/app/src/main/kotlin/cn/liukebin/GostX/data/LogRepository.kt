package cn.liukebin.GostX.data

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object LogRepository {
    @Volatile private var logFile: File? = null
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

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
        // @Volatile read: fast-path null-check before acquiring the lock.
        // logFile only ever transitions null→non-null, so this is safe.
        val file = logFile ?: return
        try {
            synchronized(this) {
                val ts = LocalTime.now().format(timeFormat)
                FileWriter(file, /* append = */ true).use { it.write("$ts $line\n") }
            }
        } catch (_: Exception) {
            // Silently ignore — logging must not affect VPN operation
        }
    }

    /** Truncates the log file to zero bytes. Creates the file if it does not exist.
     *  Keeps the file on disk so that any open file descriptors (e.g. Go's drain goroutine)
     *  remain valid; O_APPEND writers will resume from offset 0 after truncation. */
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
