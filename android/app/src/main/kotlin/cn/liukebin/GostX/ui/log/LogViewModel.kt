package cn.liukebin.gostx.ui.log

import android.app.Application
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import cn.liukebin.gostx.data.LogRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Reads bytes from [file] starting at [offset].
 * Returns a (newLines, newOffset) pair. newOffset equals file length after the read,
 * or 0 if the file does not exist.
 * If the file was truncated (length < offset), re-reads from the beginning.
 */
internal fun readFileFrom(file: File, offset: Long): Pair<List<String>, Long> {
    return try {
        if (!file.exists()) return Pair(emptyList(), 0L)
        val fileLen = file.length()
        when {
            fileLen < offset -> {
                // File was truncated; re-read from start
                val bytes = file.inputStream().use { it.readBytes() }
                Pair(String(bytes).lines().filter { it.isNotEmpty() }, fileLen)
            }
            fileLen == offset -> Pair(emptyList(), offset)
            else -> {
                val newBytes = file.inputStream().use { stream ->
                    stream.skip(offset)
                    stream.readBytes()
                }
                Pair(String(newBytes).lines().filter { it.isNotEmpty() }, fileLen)
            }
        }
    } catch (_: Exception) {
        Pair(emptyList(), 0L)
    }
}

class LogViewModel(
    application: Application,
    private val logFile: File,
    internal val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application) {

    companion object {
        /**
         * Maximum number of log lines retained in memory. Bounds memory use and
         * keeps [copyAll] safe even under sustained high-volume logging, where the
         * on-disk rotation (which keeps the newest half of the file in place) is
         * not detected as a truncation by the file-offset heuristic and would
         * otherwise let the in-memory list grow without bound. Only the most
         * recent lines are kept, mirroring the on-disk size budget.
         */
        internal const val MAX_LINES = 10000

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.AndroidViewModelFactory() {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras,
            ): T {
                val app = checkNotNull(extras[APPLICATION_KEY])
                LogRepository.init(app)
                return LogViewModel(app, LogRepository.getLogFile()) as T
            }
        }
    }

    // Backing buffer; _lines holds a capped, immutable snapshot for collectors.
    private val _buffer = mutableListOf<String>()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val _isFollowing = MutableStateFlow(true)
    val isFollowing: StateFlow<Boolean> = _isFollowing.asStateFlow()

    private var fileOffset: Long = 0L
    private val readMutex = Mutex()
    @Volatile private var pollJob: Job? = null

    /**
     * Publishes a capped snapshot of [_buffer] to [_lines]. Trims the oldest
     * lines when over [MAX_LINES] so memory stays bounded regardless of how the
     * on-disk file is rotated.
     */
    private fun publish() {
        if (_buffer.size > MAX_LINES) {
            _buffer.subList(0, _buffer.size - MAX_LINES).clear()
        }
        _lines.value = _buffer.toList()
    }

    /** Loads existing log file content into [lines]. Call before [startPolling]. */
    fun loadInitial() {
        viewModelScope.launch(ioDispatcher) {
            readMutex.withLock {
                val (lines, offset) = readFileFrom(logFile, 0L)
                _buffer.clear()
                _buffer.addAll(lines)
                fileOffset = offset
                publish()
            }
        }
    }

    /** Starts periodic polling. New lines are always appended regardless of
     *  [isFollowing]; [isFollowing] controls only whether the view auto-scrolls. */
    fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(ioDispatcher) {
            while (isActive) {
                delay(1000)
                appendNewLines()
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun appendNewLines() {
        readMutex.withLock {
            if (!logFile.exists()) {
                if (_buffer.isNotEmpty()) {
                    _buffer.clear()
                    _lines.value = emptyList()
                    fileOffset = 0L
                }
                return
            }
            val prevOffset = fileOffset
            val (newLines, newOffset) = readFileFrom(logFile, fileOffset)
            val wasTruncated = newOffset < prevOffset
            fileOffset = newOffset
            when {
                wasTruncated -> {
                    _buffer.clear()
                    _buffer.addAll(newLines)   // replace on truncation
                    publish()
                }
                newLines.isNotEmpty() -> {
                    _buffer.addAll(newLines)
                    publish()
                }
                // No new bytes and not truncated: skip the emit.
            }
        }
    }

    /** Sets live-tail mode.
     *  - `true`: enables auto-scroll and immediately reads any missed lines.
     *  - `false`: disables auto-scroll; polling continues so [lines] stays current.
     */
    @MainThread
    fun setFollowing(value: Boolean) {
        _isFollowing.value = value
        if (value) viewModelScope.launch(ioDispatcher) { appendNewLines() }
    }

    /** Toggles live-tail mode. */
    @MainThread
    fun toggleFollow() = setFollowing(!_isFollowing.value)

    /** Returns current in-memory log lines joined for clipboard copy.
     *  Uses the already-loaded [lines] buffer to avoid reading the log file
     *  on the main thread. Line count is bounded by Go's file rotation limit. */
    fun copyAll(): String = lines.value.joinToString("\n")

    /** Truncates the log file and clears the displayed lines. */
    fun clearLog() {
        viewModelScope.launch(ioDispatcher) {
            readMutex.withLock {
                LogRepository.deleteLog()
                _buffer.clear()
                _lines.value = emptyList()
                fileOffset = 0L
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}
