package cn.liukebin.gostx

import cn.liukebin.gostx.data.LogRepository
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
        val text = logFile.readText()
        assertTrue("Expected timestamped line, got: $text",
            text.matches(Regex("\\d{2}:\\d{2}:\\d{2}\\.\\d{3} hello\n")))
    }

    @Test fun `append multiple lines accumulate in order`() {
        LogRepository.append("line1")
        LogRepository.append("line2")
        val lines = logFile.readLines()
        assertEquals(2, lines.size)
        assertTrue("Expected line1 suffix, got: ${lines[0]}", lines[0].endsWith("line1"))
        assertTrue("Expected line2 suffix, got: ${lines[1]}", lines[1].endsWith("line2"))
    }

    @Test fun `deleteLog truncates the file to empty`() {
        LogRepository.append("something")
        assertTrue(logFile.exists())
        assertTrue(logFile.length() > 0)
        LogRepository.deleteLog()
        assertTrue("file should still exist after deleteLog", logFile.exists())
        assertEquals("file should be empty after deleteLog", 0L, logFile.length())
    }

    @Test fun `append after deleteLog writes to truncated file`() {
        LogRepository.append("before")
        LogRepository.deleteLog()
        LogRepository.append("after")
        val text = logFile.readText()
        assertTrue("Expected 'after' suffix, got: $text", text.endsWith("after\n"))
        assertFalse("Old content should be gone, got: $text", text.contains("before"))
    }

    @Test fun `deleteLog is safe when file does not exist`() {
        assertFalse(logFile.exists())
        LogRepository.deleteLog()
        // writeText("") has O_CREAT semantics — file is created empty
        assertTrue(logFile.exists())
        assertEquals(0L, logFile.length())
    }
}
