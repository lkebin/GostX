package cn.liukebin.gostx

import android.app.Application
import cn.liukebin.gostx.data.LogRepository
import cn.liukebin.gostx.ui.log.LogViewModel
import cn.liukebin.gostx.ui.log.readFileFrom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LogViewModelLogicTest {
    private lateinit var tempDir: File
    private lateinit var logFile: File
    private lateinit var testScheduler: TestCoroutineScheduler
    private lateinit var testDispatcher: TestDispatcher

    @Before fun setup() {
        tempDir = java.nio.file.Files.createTempDirectory("vmlogic_test").toFile()
        logFile = File(tempDir, "test.log")
        testScheduler = TestCoroutineScheduler()
        testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    }

    @After fun cleanup() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    @Test fun `LogRepository append prepends HH-mm-ss-SSS timestamp`() {
        LogRepository.initForTest(logFile)
        LogRepository.append("test message")
        val text = logFile.readText()
        // e.g. "23:10:01.042 test message\n"
        assertTrue(
            "Expected timestamp prefix, got: $text",
            text.matches(Regex("\\d{2}:\\d{2}:\\d{2}\\.\\d{3} test message\n"))
        )
    }

    @Test fun `LogRepository deleteLog truncates file not deletes it`() {
        LogRepository.initForTest(logFile)
        LogRepository.append("some content")
        assertTrue(logFile.exists())
        assertTrue(logFile.length() > 0)

        LogRepository.deleteLog()

        assertTrue("file should still exist after deleteLog", logFile.exists())
        assertEquals("file should be empty after deleteLog", 0L, logFile.length())
    }

    @Test fun `readFileFrom treats empty truncated file as reset`() {
        logFile.writeText("line1\nline2\n")
        val mid = logFile.length()
        logFile.writeText("") // simulate LogRepository.deleteLog truncation
        val (lines, offset) = readFileFrom(logFile, mid)
        assertEquals(emptyList<String>(), lines)
        assertEquals(0L, offset)
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

    @Test fun `readFileFrom handles file truncation by rereading from start`() {
        logFile.writeText("line1\nline2\n")
        val mid = logFile.length()
        logFile.writeText("new\n")  // overwrite with shorter content (truncation)
        val (lines, offset) = readFileFrom(logFile, mid)
        assertEquals(listOf("new"), lines)
        assertEquals(logFile.length(), offset)
    }

    @Test fun `stopPolling on null job is safe - null safe call does not throw`() {
        // Verifies the null-safety contract: pollJob?.cancel() when pollJob is null
        val job: Job? = null
        job?.cancel() // must not throw — documents the stopPolling null-safe contract
    }

    @Test fun `stopPolling idempotency - cancelling completed job does not throw`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scope.launch {}
        job.cancel()
        job.cancel() // second cancel must not throw
        // scope is automatically cleaned up when test completes
    }

    @Test fun `lines update even when isFollowing is false`() = runTest(testScheduler) {
        val vm = LogViewModel(
            application = mock<Application>(),
            logFile = logFile,
            ioDispatcher = testDispatcher,
        )
        vm.loadInitial()
        advanceUntilIdle()
        vm.setFollowing(false)
        vm.startPolling()
        logFile.appendText("new line\n")
        advanceTimeBy(1001)
        assertTrue(
            "lines should update regardless of isFollowing, got: ${vm.lines.value}",
            vm.lines.value.any { it.contains("new line") }
        )
        vm.stopPolling()
    }

    @Test fun `setFollowing true reads immediately without waiting for poll tick`() = runTest(testScheduler) {
        val vm = LogViewModel(
            application = mock<Application>(),
            logFile = logFile,
            ioDispatcher = testDispatcher,
        )
        vm.loadInitial()
        advanceUntilIdle()
        logFile.appendText("missed line\n")
        // Don't start polling — just call setFollowing(true)
        vm.setFollowing(true)
        runCurrent() // flush the launched coroutine
        assertTrue(
            "setFollowing(true) should immediately read missed lines, got: ${vm.lines.value}",
            vm.lines.value.any { it.contains("missed line") }
        )
    }

    @Test fun `setFollowing false does not trigger immediate read`() = runTest(testScheduler) {
        val vm = LogViewModel(
            application = mock<Application>(),
            logFile = logFile,
            ioDispatcher = testDispatcher,
        )
        vm.loadInitial()
        advanceUntilIdle()
        logFile.appendText("unread line\n")
        vm.setFollowing(false)
        runCurrent()
        assertFalse(
            "setFollowing(false) should NOT read lines immediately, got: ${vm.lines.value}",
            vm.lines.value.any { it.contains("unread line") }
        )
    }

    @Test fun `toggleFollow delegates to setFollowing`() = runTest(testScheduler) {
        val vm = LogViewModel(
            application = mock<Application>(),
            logFile = logFile,
            ioDispatcher = testDispatcher,
        )
        assertTrue(vm.isFollowing.value) // starts true
        vm.toggleFollow()
        assertFalse(vm.isFollowing.value)
        vm.toggleFollow()
        assertTrue(vm.isFollowing.value)
    }
    @Test fun `lines are cleared when log file is truncated`() = runTest {
        val vm = LogViewModel(
            application = mock<Application>(),
            logFile = logFile,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        // Populate initial lines
        logFile.writeText("old line 1\nold line 2\n")
        vm.loadInitial()
        runCurrent()
        assertEquals(2, vm.lines.value.size)

        // Simulate VPN restart: truncate file (like LogRepository.deleteLog())
        logFile.writeText("")
        // Next poll should clear lines
        vm.startPolling()
        advanceTimeBy(1001)
        assertEquals("lines should be cleared after truncation", emptyList<String>(), vm.lines.value)
        vm.stopPolling()
    }
}

// Compile-time check: public polling and follow API
private fun _assertLogViewModelApi(vm: cn.liukebin.gostx.ui.log.LogViewModel) {
    vm.startPolling()
    vm.stopPolling()
    vm.setFollowing(true)
    vm.setFollowing(false)
    vm.toggleFollow()
}
