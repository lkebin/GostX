package cn.liukebin.gostx.ui.filemanage

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.liukebin.gostx.R
import cn.liukebin.gostx.data.FileInfo
import cn.liukebin.gostx.data.FileRepository
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FileManageViewModel(
    application: Application,
    private val repo: FileRepository = FileRepository(
        application.getExternalFilesDir(null) ?: application.filesDir
    )
) : AndroidViewModel(application) {

    private val _files = MutableStateFlow<List<FileInfo>>(emptyList())
    val files: StateFlow<List<FileInfo>> = _files.asStateFlow()

    private val _toastEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        _files.value = repo.listFiles()
    }

    fun importFile(uri: Uri) {
        viewModelScope.launch {
            val cr = getApplication<Application>().contentResolver
            val fileName = try {
                withContext(Dispatchers.IO) {
                    cr.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) cursor.getString(idx) else null
                        } else null
                    }
                }
            } catch (_: Exception) { null }
            if (fileName == null) {
                _toastEvent.emit(getApplication<Application>().getString(R.string.file_import_failed_not_found))
                return@launch
            }

            if (repo.exists(fileName)) {
                _pendingOverwrite.value = Pair(uri, fileName)
                return@launch
            }
            doImport(uri, fileName)
        }
    }

    private val _pendingOverwrite = MutableStateFlow<Pair<Uri, String>?>(null)
    val pendingOverwrite: StateFlow<Pair<Uri, String>?> = _pendingOverwrite.asStateFlow()

    fun confirmOverwrite() {
        val (uri, name) = _pendingOverwrite.value ?: return
        _pendingOverwrite.value = null
        viewModelScope.launch { doImport(uri, name) }
    }

    fun cancelOverwrite() {
        _pendingOverwrite.value = null
    }

    private suspend fun doImport(uri: Uri, name: String) {
        try {
            withContext(Dispatchers.IO) {
                val cr = getApplication<Application>().contentResolver
                val input = cr.openInputStream(uri)
                    ?: throw FileNotFoundException("cannot open URI")
                input.use { stream -> repo.importFromStream(name, stream) }
            }
            refresh()
        } catch (e: Exception) {
            val app = getApplication<Application>()
            val msg = if (e is FileNotFoundException) app.getString(R.string.file_import_failed_not_found)
                      else app.getString(R.string.file_import_failed, e.message)
            _toastEvent.emit(msg)
        }
    }

    fun renameFile(oldName: String, newName: String) {
        viewModelScope.launch {
            val result = repo.renameFile(oldName, newName)
            result.onSuccess { refresh() }
            if (result.isFailure) {
                val e = result.exceptionOrNull()!!
                val app = getApplication<Application>()
                val msg = when (e) {
                    is IllegalArgumentException -> app.getString(R.string.file_name_invalid)
                    is IllegalStateException -> app.getString(R.string.file_name_exists)
                    else -> app.getString(R.string.file_rename_failed)
                }
                _toastEvent.emit(msg)
            }
        }
    }

    fun deleteFile(name: String) {
        viewModelScope.launch {
            val result = repo.deleteFile(name)
            if (result.isFailure) {
                _toastEvent.emit(getApplication<Application>().getString(R.string.file_delete_failed))
            }
            refresh()
        }
    }

    fun exportFile(name: String, dest: Uri) {
        viewModelScope.launch {
            try {
                repo.exportToUri(name, dest, getApplication<Application>().contentResolver)
                _toastEvent.emit(getApplication<Application>().getString(R.string.file_export_done))
            } catch (e: Exception) {
                _toastEvent.emit(getApplication<Application>().getString(R.string.file_export_failed, e.message))
            }
        }
    }

    fun copyPath(name: String) {
        val app = getApplication<Application>()
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("file path", name))
        viewModelScope.launch { _toastEvent.emit(getApplication<Application>().getString(R.string.file_copy_path_done)) }
    }
}
