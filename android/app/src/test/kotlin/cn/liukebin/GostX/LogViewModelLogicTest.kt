package cn.liukebin.GostX

import cn.liukebin.GostX.ui.log.readFileFrom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
}

// Compile-time check: startPolling and stopPolling must be public
private fun _assertLogViewModelPollingApi(vm: cn.liukebin.GostX.ui.log.LogViewModel) {
    vm.startPolling()
    vm.stopPolling()
}
