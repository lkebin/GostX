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
