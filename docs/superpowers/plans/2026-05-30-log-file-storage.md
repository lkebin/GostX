# Log File Storage & Tail-F UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the in-memory log buffer with file-based storage and add a pause/resume live-tail toggle to the log screen.

**Architecture:** `LogRepository` writes log lines to `filesDir/gostx.log` (append-only, no in-memory copy); `LogViewModel` reads the file on screen entry and polls for new bytes every second when in follow mode; `GostVpnService` deletes the log file on each user-initiated `ACTION_START` / `ACTION_STOP` to start each session fresh; `LogScreen` adds a Pause/Play `IconButton` in the top-bar actions.

**Tech Stack:** Kotlin, Jetpack Compose, `AndroidViewModel` + companion `ViewModelProvider.Factory`, Kotlin Coroutines (`Dispatchers.IO`), `kotlinx.coroutines.sync.Mutex`, JUnit 4, mockito-kotlin.

---

## File Map

| Status | File | Change |
|--------|------|--------|
| Modify | `android/app/src/main/kotlin/cn/liukebin/GostX/data/LogRepository.kt` | Rewrite: drop `StateFlow<List<String>>`, add file writer |
| Create | `android/app/src/test/kotlin/cn/liukebin/GostX/LogRepositoryTest.kt` | New unit tests |
| Modify | `android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt` | Add `deleteLog()` in `onStartCommand` |
| Create | `android/app/src/main/kotlin/cn/liukebin/GostX/ui/log/LogViewModel.kt` | New: file reading, polling, follow-state |
| Create | `android/app/src/test/kotlin/cn/liukebin/GostX/LogViewModelLogicTest.kt` | Unit tests for pure `readFileFrom()` |
| Modify | `android/app/src/main/kotlin/cn/liukebin/GostX/ui/log/LogScreen.kt` | Use `LogViewModel`, add toggle button |
| Modify | `android/app/src/main/res/values/strings.xml` | Add `log_follow_on`, `log_follow_off` |
| Modify | `android/app/src/main/res/values-zh/strings.xml` | Add Chinese strings |

Go side (`go/gostlib/`) is **not touched**.

---

## Task 1: Rewrite LogRepository

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/data/LogRepository.kt`
- Create: `android/app/src/test/kotlin/cn/liukebin/GostX/LogRepositoryTest.kt`

- [ ] **Step 1: Write failing tests**

Create `android/app/src/test/kotlin/cn/liukebin/GostX/LogRepositoryTest.kt`:

```kotlin
package cn.liukebin.GostX

import cn.liukebin.GostX.data.LogRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class LogRepositoryTest {
    private lateinit var tempDir: File
    private lateinit var logFile: File

    @Before fun setup() {
        tempDir = java.nio.file.Files.createTempDirectory("log_test").toFile()
        logFile = File(tempDir, "gostx.log")
        LogRepository.initForTest(logFile)
    }

    @After fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test fun `append writes line to file`() {
        LogRepository.append("hello")
        assertEquals("hello\n", logFile.readText())
    }

    @Test fun `append multiple lines accumulate in order`() {
        LogRepository.append("line1")
        LogRepository.append("line2")
        assertEquals("line1\nline2\n", logFile.readText())
    }

    @Test fun `deleteLog removes the file`() {
        LogRepository.append("something")
        LogRepository.deleteLog()
        assertFalse(logFile.exists())
    }

    @Test fun `append after deleteLog recreates the file`() {
        LogRepository.append("before")
        LogRepository.deleteLog()
        LogRepository.append("after")
        assertEquals("after\n", logFile.readText())
    }

    @Test fun `deleteLog is safe when file does not exist`() {
        assertFalse(logFile.exists())
        LogRepository.deleteLog() // must not throw
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "cn.liukebin.GostX.LogRepositoryTest" 2>&1 | tail -20
```

Expected: compilation error — `initForTest` and file-based `append`/`deleteLog` don't exist yet.

- [ ] **Step 3: Rewrite LogRepository**

Replace the entire content of `android/app/src/main/kotlin/cn/liukebin/GostX/data/LogRepository.kt`:

```kotlin
package cn.liukebin.GostX.data

import android.content.Context
import java.io.File
import java.io.FileWriter

object LogRepository {
    @Volatile private var logFile: File? = null

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
            logFile?.delete()
        } catch (_: Exception) {}
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "cn.liukebin.GostX.LogRepositoryTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` — all 5 tests pass.

- [ ] **Step 5: Commit**

```bash
cd /path/to/GostX
git add android/app/src/main/kotlin/cn/liukebin/GostX/data/LogRepository.kt \
        android/app/src/test/kotlin/cn/liukebin/GostX/LogRepositoryTest.kt
git commit -m "refactor(log): replace in-memory StateFlow with file-based LogRepository

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Task 2: Add String Resources

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values-zh/strings.xml`

- [ ] **Step 1: Add English strings**

In `android/app/src/main/res/values/strings.xml`, add inside `<resources>` after the `log_empty` entry:

```xml
    <string name="log_follow_on">Resume live tail</string>
    <string name="log_follow_off">Pause live tail</string>
```

- [ ] **Step 2: Add Chinese strings**

In `android/app/src/main/res/values-zh/strings.xml`, add inside `<resources>` after the `log_empty` entry:

```xml
    <string name="log_follow_on">开启动态载入</string>
    <string name="log_follow_off">暂停动态载入</string>
```

- [ ] **Step 3: Verify build succeeds**

```bash
cd android && ./gradlew :app:assembleDebug 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/res/values/strings.xml \
        android/app/src/main/res/values-zh/strings.xml
git commit -m "feat(log): add follow toggle string resources

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Task 3: Update GostVpnService — Delete Log on Start/Stop

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt`

The log file must be deleted whenever the user intentionally starts or stops the VPN. The reconnect path (`stopVpn(updatePersistentState = false)` → `startVpn()`) must **not** delete the file — reconnects are mid-session, not new sessions.

The deletion is placed in `onStartCommand` so it only runs for user-initiated actions, not for the reconnect path.

- [ ] **Step 1: Initialize LogRepository in onCreate**

`LogRepository.append()` silently skips if `logFile` is null, so it must be initialized before the first `log()` call. In `GostVpnService.onCreate()`, add `LogRepository.init(this)` after `NotificationHelper.createChannel(this)`:

```kotlin
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        LogRepository.init(this)
        configRepo = ConfigRepository(getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE))
    }
```

- [ ] **Step 2: Add deleteLog calls in onStartCommand**

Find this block in `onStartCommand`:

```kotlin
        when (intent?.action) {
            ACTION_START -> scope.launch { startVpn() }
            ACTION_STOP -> scope.launch { stopVpn() }
        }
```

Replace it with:

```kotlin
        when (intent?.action) {
            ACTION_START -> scope.launch {
                LogRepository.deleteLog()
                startVpn()
            }
            ACTION_STOP -> scope.launch {
                LogRepository.deleteLog()
                stopVpn()
            }
        }
```

`LogRepository` is already imported in this file (`import cn.liukebin.GostX.data.LogRepository`). If the import is missing, add it.

Also, `GostVpnService.log()` still calls `LogRepository.append(msg)` — that line is unchanged.

- [ ] **Step 3: Verify build**

```bash
cd android && ./gradlew :app:assembleDebug 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/service/GostVpnService.kt
git commit -m "feat(log): init LogRepository in onCreate; delete log on user start/stop

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Task 4: Create LogViewModel

**Files:**
- Create: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/log/LogViewModel.kt`
- Create: `android/app/src/test/kotlin/cn/liukebin/GostX/LogViewModelLogicTest.kt`

The ViewModel exposes `lines: StateFlow<List<String>>` and `isFollowing: StateFlow<Boolean>`. Core file-reading logic is extracted into the pure internal function `readFileFrom(file, offset)` so it can be unit-tested without Android or coroutine infrastructure.

- [ ] **Step 1: Write failing tests for the pure readFileFrom function**

Create `android/app/src/test/kotlin/cn/liukebin/GostX/LogViewModelLogicTest.kt`:

```kotlin
package cn.liukebin.GostX

import cn.liukebin.GostX.ui.log.readFileFrom
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class LogViewModelLogicTest {
    private lateinit var tempDir: File
    private lateinit var logFile: File

    @Before fun setup() {
        tempDir = java.nio.file.Files.createTempDirectory("vmlogic_test").toFile()
        logFile = File(tempDir, "test.log")
    }

    @After fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test fun `readFileFrom nonexistent file returns empty list and zero offset`() {
        val (lines, offset) = readFileFrom(logFile, 0L)
        assertEquals(emptyList<String>(), lines)
        assertEquals(0L, offset)
    }

    @Test fun `readFileFrom reads all lines from offset zero`() {
        logFile.writeText("line1\nline2\nline3\n")
        val (lines, offset) = readFileFrom(logFile, 0L)
        assertEquals(listOf("line1", "line2", "line3"), lines)
        assertEquals(logFile.length(), offset)
    }

    @Test fun `readFileFrom at end of file returns empty list and same offset`() {
        logFile.writeText("line1\n")
        val end = logFile.length()
        val (lines, offset) = readFileFrom(logFile, end)
        assertEquals(emptyList<String>(), lines)
        assertEquals(end, offset)
    }

    @Test fun `readFileFrom reads only new bytes since offset`() {
        logFile.writeText("line1\n")
        val mid = logFile.length()
        logFile.appendText("line2\nline3\n")
        val (lines, offset) = readFileFrom(logFile, mid)
        assertEquals(listOf("line2", "line3"), lines)
        assertEquals(logFile.length(), offset)
    }

    @Test fun `readFileFrom filters blank lines`() {
        logFile.writeText("a\n\nb\n")
        val (lines, _) = readFileFrom(logFile, 0L)
        assertEquals(listOf("a", "b"), lines)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "cn.liukebin.GostX.LogViewModelLogicTest" 2>&1 | tail -20
```

Expected: compilation error — `readFileFrom` not found.

- [ ] **Step 3: Create LogViewModel.kt**

Create `android/app/src/main/kotlin/cn/liukebin/GostX/ui/log/LogViewModel.kt`:

```kotlin
package cn.liukebin.GostX.ui.log

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import cn.liukebin.GostX.data.LogRepository
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
 */
internal fun readFileFrom(file: File, offset: Long): Pair<List<String>, Long> {
    if (!file.exists()) return Pair(emptyList(), 0L)
    val fileLen = file.length()
    if (fileLen <= offset) return Pair(emptyList(), offset)
    val newBytes = file.inputStream().use { stream ->
        stream.skip(offset)
        stream.readBytes()
    }
    val lines = String(newBytes).lines().filter { it.isNotEmpty() }
    return Pair(lines, fileLen)
}

class LogViewModel(
    application: Application,
    private val logFile: File,
) : AndroidViewModel(application) {

    companion object {
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

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val _isFollowing = MutableStateFlow(true)
    val isFollowing: StateFlow<Boolean> = _isFollowing.asStateFlow()

    private var fileOffset: Long = 0L
    private val readMutex = Mutex()
    private var pollJob: Job? = null

    /** Call when the log screen enters composition to load existing content and start polling. */
    fun loadInitial() {
        viewModelScope.launch(Dispatchers.IO) {
            readMutex.withLock {
                val (lines, offset) = readFileFrom(logFile, 0L)
                _lines.value = lines.takeLast(2000)
                fileOffset = offset
            }
            startPolling()
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000)
                if (_isFollowing.value) appendNewLines()
            }
        }
    }

    private suspend fun appendNewLines() {
        readMutex.withLock {
            if (!logFile.exists()) {
                if (_lines.value.isNotEmpty()) {
                    _lines.value = emptyList()
                    fileOffset = 0L
                }
                return
            }
            val (newLines, newOffset) = readFileFrom(logFile, fileOffset)
            fileOffset = newOffset
            if (newLines.isNotEmpty()) {
                _lines.value = (_lines.value + newLines).takeLast(2000)
            }
        }
    }

    /** Toggle live-tail mode. Resuming immediately reads all missed lines. */
    fun toggleFollow() {
        val nowFollowing = !_isFollowing.value
        _isFollowing.value = nowFollowing
        if (nowFollowing) {
            viewModelScope.launch(Dispatchers.IO) { appendNewLines() }
        }
    }

    /** Returns full file contents for clipboard copy. Does not depend on in-memory [lines]. */
    fun copyAll(): String = try { logFile.readText() } catch (_: Exception) { "" }

    /** Deletes the log file and clears the displayed lines. */
    fun clearLog() {
        LogRepository.deleteLog()
        _lines.value = emptyList()
        fileOffset = 0L
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "cn.liukebin.GostX.LogViewModelLogicTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` — all 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/ui/log/LogViewModel.kt \
        android/app/src/test/kotlin/cn/liukebin/GostX/LogViewModelLogicTest.kt
git commit -m "feat(log): add LogViewModel with file-based polling and follow toggle

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Task 5: Update LogScreen

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/GostX/ui/log/LogScreen.kt`

- [ ] **Step 1: Replace LogScreen.kt**

Replace the entire file content with:

```kotlin
package cn.liukebin.GostX.ui.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.liukebin.GostX.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    onBack: () -> Unit,
    viewModel: LogViewModel = viewModel(factory = LogViewModel.Factory),
) {
    val lines by viewModel.lines.collectAsState()
    val isFollowing by viewModel.isFollowing.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.loadInitial()
    }

    // Auto-scroll to the latest line only when live-tail is active.
    // Also fires when isFollowing flips to true so the view jumps to bottom on resume.
    LaunchedEffect(lines.size, isFollowing) {
        if (isFollowing && lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleFollow() }) {
                        Icon(
                            imageVector = if (isFollowing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (isFollowing) R.string.log_follow_off else R.string.log_follow_on
                            )
                        )
                    }
                    TextButton(onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("gostx_log", viewModel.copyAll()))
                    }) { Text(stringResource(R.string.log_copy)) }
                    TextButton(onClick = { viewModel.clearLog() }) { Text(stringResource(R.string.log_clear)) }
                }
            )
        }
    ) { padding ->
        if (lines.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.log_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(lines) { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify full build and all existing unit tests pass**

```bash
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`, all tests pass (including existing `GostVpnServicePolicyTest`, `HomeViewModelLogicTest`, etc.).

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/cn/liukebin/GostX/ui/log/LogScreen.kt
git commit -m "feat(log): use LogViewModel, add live-tail pause/resume toggle button

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Post-Implementation Smoke Test (Manual)

After building and installing the APK:

1. Start the VPN service → open the log page → verify logs appear and scroll automatically.
2. Tap the **Pause** icon (top-right) → scroll up to browse → confirm no new lines appear while paused.
3. Tap **Play** → confirm the view catches up to all missed lines and jumps to the bottom.
4. Stop the VPN → reopen the log page → verify only the stop-session log entries are shown (previous run's logs are gone).
5. Start the VPN again → verify only the new session's logs appear.
6. Tap **Copy** → paste in a text editor → verify full file content is copied (not truncated to 2000 lines if the session was long).
7. Tap **Clear** → verify list empties immediately.
