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
