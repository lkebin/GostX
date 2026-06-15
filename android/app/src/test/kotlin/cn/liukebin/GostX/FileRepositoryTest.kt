package cn.liukebin.gostx

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
    fun `importFromStream writes file content`() = runBlocking {
        val source = File(tempDir, "source.dat").also { it.writeText("imported") }
        val info = repo.importFromStream("target.dat", source.inputStream())
        assertEquals("target.dat", info.name)
        assertEquals("imported", File(tempDir, "target.dat").readText())
    }

    @Test
    fun `listFiles sorted by name`() {
        File(tempDir, "b.txt").writeText("")
        File(tempDir, "a.txt").writeText("")
        Thread.sleep(10)
        File(tempDir, "c.txt").writeText("")
        val names = repo.listFiles().map { it.name }
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), names)
    }
}
