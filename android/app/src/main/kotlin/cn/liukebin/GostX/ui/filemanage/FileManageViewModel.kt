package cn.liukebin.gostx.ui.filemanage

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
                _toastEvent.emit("导入失败：找不到文件")
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
            val msg = if (e is FileNotFoundException) "导入失败：找不到文件"
                      else "导入失败：${e.message}"
            _toastEvent.emit(msg)
        }
    }

    fun renameFile(oldName: String, newName: String) {
        viewModelScope.launch {
            val result = repo.renameFile(oldName, newName)
            result.onSuccess { refresh() }
            if (result.isFailure) {
                val e = result.exceptionOrNull()!!
                val msg = when (e) {
                    is IllegalArgumentException -> "文件名不能为空或包含非法字符"
                    is IllegalStateException -> "文件名已存在"
                    else -> "重命名失败"
                }
                _toastEvent.emit(msg)
            }
        }
    }

    fun deleteFile(name: String) {
        viewModelScope.launch {
            val result = repo.deleteFile(name)
            if (result.isFailure) {
                _toastEvent.emit("删除失败")
            }
            refresh()
        }
    }

    fun copyPath(name: String) {
        val app = getApplication<Application>()
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("file path", name))
        viewModelScope.launch { _toastEvent.emit("已复制到剪贴板") }
    }
}
