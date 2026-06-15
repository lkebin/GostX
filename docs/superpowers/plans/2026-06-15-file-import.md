# File Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a file management page where users can import files via SAF into the gost working directory, rename/delete them, and copy relative paths to clipboard.

**Architecture:** A new `FileManageScreen` accessible from SettingsScreen lists files in the app's external files directory. A FAB triggers SAF `ACTION_OPEN_DOCUMENT` to import files. The screen provides per-file overflow actions (rename, delete, copy path) via dropdown menus. A `FileRepository` in the data layer encapsulates all file I/O operations on the workDir.

**Tech Stack:** Jetpack Compose (Material3), AndroidX ViewModel, Navigation Compose, SAF (Storage Access Framework), kotlinx.coroutines

---

### Task 1: Add string resources

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values-zh/strings.xml`

- [ ] **Step 1: Add default strings (values/strings.xml)**

Add these strings before the closing `</resources>` tag:

```xml
<!-- File management -->
<string name="file_manage_title">文件管理</string>
<string name="file_manage_empty">暂无文件，点击右下角导入</string>
<string name="file_import">导入</string>
<string name="file_rename">重命名</string>
<string name="file_rename_title">重命名文件</string>
<string name="file_delete">删除</string>
<string name="file_delete_confirm_title">删除文件</string>
<string name="file_delete_confirm_message">确定要删除 \"%s\" 吗？</string>
<string name="file_copy_path">复制路径</string>
<string name="file_copy_path_done">已复制到剪贴板</string>
<string name="file_name_label">文件名</string>
<string name="file_name_exists">文件名已存在</string>
<string name="file_name_invalid">文件名不能为空或包含非法字符</string>
<string name="file_import_failed_not_found">导入失败：找不到文件</string>
<string name="file_import_failed">导入失败：%s</string>
<string name="file_overwrite_title">文件已存在</string>
<string name="file_overwrite_message">文件 \"%s\" 已存在，覆盖？</string>
<string name="file_overwrite_confirm">覆盖</string>
```

- [ ] **Step 2: Clone same strings to values-zh/strings.xml**

Strings are identical (Chinese), so add the same block to `values-zh/strings.xml` before `</resources>`.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/res/values/strings.xml android/app/src/main/res/values-zh/strings.xml
git commit -m "feat: add string resources for file management screen"
```

---

### Task 2: Create FileRepository

**Files:**
- Create: `android/app/src/main/kotlin/cn/liukebin/GostX/data/FileRepository.kt`
- Create: `android/app/src/test/kotlin/cn/liukebin/GostX/FileRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

`FileRepositoryTest.kt`:
```kotlin
package cn.liukebin.gostx

import android.net.Uri
import cn.liukebin.gostx.data.FileInfo
import cn.liukebin.gostx.data.FileRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FileRepositoryTest {
    private lateinit var tempDir: File
    private lateinit var repo: FileRepository

    @Before
    fun setup() {
        tempDir = java.nio.file.Files.createTempDirectory("file_repo_test").toFile()
        repo = FileRepository(tempDir)
    }

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `listFiles returns empty when directory is empty`() {
        assertTrue(repo.listFiles().isEmpty())
    }

    @Test
    fun `listFiles returns FileInfo for regular files only`() {
        File(tempDir, "test.txt").writeText("hello")
        File(tempDir, ".hidden").writeText("secret")
        File(tempDir, "subdir").mkdir()
        val files = repo.listFiles()
        assertEquals(1, files.size)
        assertEquals("test.txt", files[0].name)
        assertEquals(5L, files[0].sizeBytes)
    }

    @Test
    fun `deleteFile removes file`() {
        File(tempDir, "bye.txt").writeText("x")
        val result = repo.deleteFile("bye.txt")
        assertTrue(result.isSuccess)
        assertFalse(File(tempDir, "bye.txt").exists())
    }

    @Test
    fun `deleteFile succeeds when file does not exist`() {
        val result = repo.deleteFile("nonexistent.txt")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `renameFile succeeds for valid rename`() {
        File(tempDir, "old.txt").writeText("data")
        val result = repo.renameFile("old.txt", "new.txt")
        assertTrue(result.isSuccess)
        assertFalse(File(tempDir, "old.txt").exists())
        assertTrue(File(tempDir, "new.txt").exists())
        assertEquals("data", File(tempDir, "new.txt").readText())
    }

    @Test
    fun `renameFile fails when new name already exists`() {
        File(tempDir, "a.txt").writeText("a")
        File(tempDir, "b.txt").writeText("b")
        val result = repo.renameFile("a.txt", "b.txt")
        assertTrue(result.isFailure)
    }

    @Test
    fun `renameFile fails when new name contains slash`() {
        File(tempDir, "a.txt").writeText("a")
        val result = repo.renameFile("a.txt", "bad/name.txt")
        assertTrue(result.isFailure)
    }

    @Test
    fun `renameFile fails when new name is empty`() {
        File(tempDir, "a.txt").writeText("a")
        val result = repo.renameFile("a.txt", "")
        assertTrue(result.isFailure)
    }

    @Test
    fun `exists returns correct values`() {
        File(tempDir, "real.txt").writeText("real")
        assertTrue(repo.exists("real.txt"))
        assertFalse(repo.exists("fake.txt"))
    }

    @Test
    fun `importFromUri writes file content`() = runBlocking {
        val source = File(tempDir, "source.dat").also { it.writeText("imported") }
        // Simulate: read from a file:// URI. The real caller uses a ContentResolver,
        // but for testing we can call importFromStream directly.
        val info = File(tempDir, "target.dat").outputStream().use { out ->
            repo.importFromStream("target.dat", source.inputStream())
        }
        assertEquals("target.dat", info.name)
        assertEquals("imported", File(tempDir, "target.dat").readText())
    }

    @Test
    fun `listFiles sorted by name`() {
        File(tempDir, "b.txt").writeText("")
        File(tempDir, "a.txt").writeText("")
        Thread.sleep(10) // ensure distinct mtimes
        File(tempDir, "c.txt").writeText("")
        val names = repo.listFiles().map { it.name }
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), names)
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "cn.liukebin.gostx.FileRepositoryTest"`
Expected: FAIL (FileRepository class not found)

- [ ] **Step 2: Write minimal FileRepository implementation**

`FileRepository.kt`:
```kotlin
package cn.liukebin.gostx.data

import java.io.File
import java.io.InputStream

data class FileInfo(
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long
)

class FileRepository(private val workDir: File) {

    fun listFiles(): List<FileInfo> {
        if (!workDir.isDirectory) return emptyList()
        return workDir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.map { FileInfo(it.name, it.length(), it.lastModified()) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun exists(name: String): Boolean = File(workDir, name).isFile

    fun importFromStream(name: String, input: InputStream): FileInfo {
        val target = File(workDir, name)
        target.outputStream().use { output -> input.copyTo(output) }
        return FileInfo(name, target.length(), target.lastModified())
    }

    fun renameFile(oldName: String, newName: String): Result<Unit> {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed.contains("/") || trimmed.contains(" ")) {
            return Result.failure(IllegalArgumentException("invalid filename"))
        }
        val oldFile = File(workDir, oldName)
        val newFile = File(workDir, trimmed)
        if (newFile.exists()) {
            return Result.failure(IllegalStateException("file already exists"))
        }
        return if (oldFile.renameTo(newFile)) {
            Result.success(Unit)
        } else {
            Result.failure(IOException("rename failed"))
        }
    }

    fun deleteFile(name: String): Result<Unit> {
        val file = File(workDir, name)
        if (file.exists() && !file.isFile) {
            return Result.failure(IllegalStateException("not a regular file"))
        }
        file.delete() // no-op if doesn't exist
        return Result.success(Unit)
    }
}
```

- [ ] **Step 3: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.liukebin.gostx.FileRepositoryTest"`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/data/FileRepository.kt \
        android/app/src/test/kotlin/cn/liukebin/GostX/FileRepositoryTest.kt
git commit -m "feat: add FileRepository for managing files in work directory"
```

---

### Task 3: Create FileManageViewModel

**Files:**
- Create: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/filemanage/FileManageViewModel.kt`
- Create: `android/app/src/test/kotlin/cn/liukebin/GostX/FileManageViewModelTest.kt`

- [ ] **Step 1: Write the failing test**

`FileManageViewModelTest.kt`:
```kotlin
package cn.liukebin.gostx

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import cn.liukebin.gostx.data.FileInfo
import cn.liukebin.gostx.data.FileRepository
import cn.liukebin.gostx.ui.filemanage.FileManageViewModel
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class FileManageViewModelTest {
    private lateinit var tempDir: File
    private lateinit var repo: FileRepository
    private lateinit var clipboard: ClipboardManager
    private lateinit var app: Application
    private val testFileInfo = FileInfo("test.txt", 100L, System.currentTimeMillis())

    @Before
    fun setup() {
        tempDir = java.nio.file.Files.createTempDirectory("vm_test").toFile()
        repo = FileRepository(tempDir)
        clipboard = mock {
            on { setPrimaryClip(any()) } doReturn Unit
        }
        app = mock {
            on { getSystemService(Context.CLIPBOARD_SERVICE) } doReturn clipboard
            on { getExternalFilesDir(null) } doReturn tempDir
            on { filesDir } doReturn tempDir
        }
    }

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `files starts empty when no files present`() = runTest {
        val vm = FileManageViewModel(app, repo)
        assertEquals(emptyList<FileInfo>(), vm.files.first())
    }

    @Test
    fun `copyPath puts relative path on clipboard and emits toast`() = runTest {
        val vm = FileManageViewModel(app, repo)
        vm.refresh() // ensure list loaded
        vm.copyPath("myfile.txt")
        val captor = argumentCaptor<ClipData>()
        verify(clipboard).setPrimaryClip(captor.capture())
        assertEquals("myfile.txt", captor.firstValue.getItemAt(0).text.toString())
        assertEquals("已复制到剪贴板", vm.toastEvent.first())
    }

    @Test
    fun `deleteFile removes from list`() = runTest {
        val f = File(tempDir, "del.txt").also { it.writeText("x") }
        val vm = FileManageViewModel(app, repo)
        vm.refresh()
        assertEquals(1, vm.files.value.size)
        vm.deleteFile("del.txt")
        assertFalse(f.exists())
        assertEquals(0, vm.files.first().size)
    }

    @Test
    fun `renameFile updates list`() = runTest {
        File(tempDir, "old.txt").writeText("data")
        val vm = FileManageViewModel(app, repo)
        vm.refresh()
        vm.renameFile("old.txt", "new.txt")
        val files = vm.files.first()
        assertEquals(1, files.size)
        assertEquals("new.txt", files[0].name)
        assertFalse(File(tempDir, "old.txt").exists())
    }

    @Test
    fun `renameFile emits toast on duplicate`() = runTest {
        File(tempDir, "a.txt").writeText("a")
        File(tempDir, "b.txt").writeText("b")
        val vm = FileManageViewModel(app, repo)
        vm.refresh()
        vm.renameFile("a.txt", "b.txt")
        assertEquals("文件名已存在", vm.toastEvent.first())
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "cn.liukebin.gostx.FileManageViewModelTest"`
Expected: FAIL (FileManageViewModel class not found)

- [ ] **Step 2: Write FileManageViewModel**

`FileManageViewModel.kt`:
```kotlin
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
            } catch (_: Exception) { null } ?: return@launch

            if (repo.exists(fileName)) {
                _pendingOverwrite.value = Pair(uri, fileName)
                return@launch
            }
            doImport(uri, fileName)
        }
    }

    // null = no pending overwrite, Pair(uri, filename) = awaiting confirmation
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
        repo.renameFile(oldName, newName)
            .onSuccess { refresh() }
            .onFailure { e ->
                viewModelScope.launch {
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
        repo.deleteFile(name)
        refresh()
    }

    fun copyPath(name: String) {
        val app = getApplication<Application>()
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("file path", name))
        viewModelScope.launch { _toastEvent.emit("已复制到剪贴板") }
    }
}
```

- [ ] **Step 3: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.liukebin.gostx.FileManageViewModelTest"`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/ui/filemanage/FileManageViewModel.kt \
        android/app/src/test/kotlin/cn/liukebin/GostX/FileManageViewModelTest.kt
git commit -m "feat: add FileManageViewModel with import/rename/delete/copy path"
```

---

### Task 4: Add Screen navigation route

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/Navigation.kt`

- [ ] **Step 1: Add FileManage screen constant to sealed class**

Add to the `Screen` sealed class body:

```kotlin
object FileManage : Screen("fileManage")
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/ui/Navigation.kt
git commit -m "feat: add FileManage navigation route"
```

---

### Task 5: Create FileManageScreen and wire up navigation

**Files:**
- Create: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/filemanage/FileManageScreen.kt`
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/settings/SettingsScreen.kt`
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/MainActivity.kt`

- [ ] **Step 1: Write FileManageScreen**

`FileManageScreen.kt`:
```kotlin
package cn.liukebin.gostx.ui.filemanage

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.liukebin.gostx.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
}

private fun formatTime(millis: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return fmt.format(Date(millis))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManageScreen(
    onBack: () -> Unit = {},
    vm: FileManageViewModel = viewModel(
        factory = remember {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras
                ): T {
                    val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                    return FileManageViewModel(app) as T
                }
            }
        }
    )
) {
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) vm.importFile(uri)
    }

    val files by vm.files.collectAsState()
    val pendingOverwrite by vm.pendingOverwrite.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.toastEvent.collect { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
        }
    }

    // Overwrite confirmation dialog
    if (pendingOverwrite != null) {
        AlertDialog(
            title = { Text(stringResource(R.string.file_overwrite_title)) },
            text = {
                Text(stringResource(R.string.file_overwrite_message, pendingOverwrite!!.second))
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmOverwrite() }) {
                    Text(stringResource(R.string.file_overwrite_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelOverwrite() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            onDismissRequest = { vm.cancelOverwrite() }
        )
    }

    var renameTarget by remember { mutableStateOf<String?>(null) }

    // Rename dialog
    if (renameTarget != null) {
        var renameInput by remember(renameTarget) { mutableStateOf(renameTarget!!) }
        AlertDialog(
            title = { Text(stringResource(R.string.file_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text(stringResource(R.string.file_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.renameFile(renameTarget!!, renameInput)
                        renameTarget = null
                    },
                    enabled = renameInput.isNotBlank()
                ) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            onDismissRequest = { renameTarget = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.file_manage_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.file_import))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.file_manage_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(paddingValues)
            ) {
                items(files, key = { it.name }) { file ->
                    FileListItem(
                        file = file,
                        onRename = { renameTarget = it.name },
                        onDelete = { vm.deleteFile(it.name) },
                        onCopyPath = { vm.copyPath(it.name) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FileListItem(
    file: cn.liukebin.gostx.data.FileInfo,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopyPath: () -> Unit
) {
    var deletePending by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    // Delete confirmation dialog
    if (deletePending) {
        AlertDialog(
            title = { Text(stringResource(R.string.file_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.file_delete_confirm_message, file.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    deletePending = false
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletePending = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            onDismissRequest = { deletePending = false }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopyPath)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${formatSize(file.sizeBytes)}  ${formatTime(file.lastModified)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.file_rename)) },
                    onClick = {
                        menuExpanded = false
                        onRename()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.file_copy_path)) },
                    onClick = {
                        menuExpanded = false
                        onCopyPath()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.file_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        deletePending = true
                    }
                )
            }
        }
    }
}
```

- [ ] **Step 2: Add "文件管理" entry to SettingsScreen**

In `SettingsScreen.kt`, add the import:

```kotlin
import cn.liukebin.gostx.ui.Screen
```

Add `onNavigateToFileManage` parameter to `SettingsScreen` signature. Add it after `onNavigateToAppFilter`:

```kotlin
fun SettingsScreen(
    repo: ConfigRepository,
    onNavigateToAppFilter: () -> Unit = {},
    onNavigateToFileManage: () -> Unit = {},  // <-- add this line
    onBack: () -> Unit = {},
```

Append a divider + the file management row in the settings body, just before the per-app proxy section (or after it). For example, after the logging toggle's row and divider, add:

```kotlin
SettingItem(
    icon = Icons.Outlined.FolderOpen,
    title = stringResource(R.string.file_manage_title),
    onClick = onNavigateToFileManage,
    trailing = {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = stringResource(R.string.nav_config),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
)

HorizontalDivider()
```

Also add the missing import for `Icons.Outlined.FolderOpen`:
```kotlin
import androidx.compose.material.icons.outlined.FolderOpen
```

- [ ] **Step 3: Wire up in MainActivity.kt**

Add import:
```kotlin
import cn.liukebin.gostx.ui.filemanage.FileManageScreen
```

Add the composable block after the `AppFilter` composable:

```kotlin
composable(Screen.FileManage.route) {
    FileManageScreen(
        onBack = { navController.popBackStack() }
    )
}
```

And add `onNavigateToFileManage` to the SettingsScreen invocation in its composable:
```kotlin
composable(Screen.Settings.route) {
    SettingsScreen(
        repo = configRepository,
        onNavigateToAppFilter = { navController.navigate(Screen.AppFilter.route) },
        onNavigateToFileManage = { navController.navigate(Screen.FileManage.route) },
        onBack = { navController.popBackStack() }
    )
}
```

- [ ] **Step 4: Build and verify compilation**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/ui/filemanage/FileManageScreen.kt \
        android/app/src/main/kotlin/cn/liukebin/GostX/ui/settings/SettingsScreen.kt \
        android/app/src/main/kotlin/cn/liukebin/GostX/MainActivity.kt
git commit -m "feat: add FileManageScreen with SAF import, rename, delete, and clipboard copy"
```
