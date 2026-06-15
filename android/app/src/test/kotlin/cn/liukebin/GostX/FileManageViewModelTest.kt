package cn.liukebin.gostx

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import cn.liukebin.gostx.data.FileInfo
import cn.liukebin.gostx.data.FileRepository
import cn.liukebin.gostx.ui.filemanage.FileManageViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
class FileManageViewModelTest {
    private lateinit var tempDir: File
    private lateinit var repo: FileRepository
    private lateinit var clipboard: ClipboardManager
    private lateinit var app: Application
    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setup() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        tempDir = java.nio.file.Files.createTempDirectory("vm_test").toFile()
        repo = FileRepository(tempDir)
        clipboard = mock()
        app = mock {
            on { getSystemService(Context.CLIPBOARD_SERVICE) } doReturn clipboard
            on { getExternalFilesDir(null) } doReturn tempDir
            on { filesDir } doReturn tempDir
        }
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    @Test
    fun `files starts empty when no files present`() = runTest(dispatcher) {
        val vm = FileManageViewModel(app, repo)
        assertEquals(emptyList<FileInfo>(), vm.files.first())
    }

    @Test
    fun `copyPath puts relative path on clipboard and emits toast`() = runTest(dispatcher) {
        val vm = FileManageViewModel(app, repo)
        vm.refresh()
        val clip = mock<ClipData>()
        mockStatic(ClipData::class.java).use { clipMock ->
            clipMock.`when`<ClipData> { ClipData.newPlainText(any(), any()) }.thenReturn(clip)
            vm.copyPath("myfile.txt")
        }
        val captor = argumentCaptor<ClipData>()
        verify(clipboard).setPrimaryClip(captor.capture())
        assertEquals(clip, captor.firstValue)
        assertEquals("已复制到剪贴板", vm.toastEvent.first())
    }

    @Test
    fun `deleteFile removes from list`() = runTest(dispatcher) {
        val f = File(tempDir, "del.txt").also { it.writeText("x") }
        val vm = FileManageViewModel(app, repo)
        vm.refresh()
        assertEquals(1, vm.files.value.size)
        vm.deleteFile("del.txt")
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(f.exists())
        assertEquals(0, vm.files.first().size)
    }

    @Test
    fun `renameFile updates list`() = runTest(dispatcher) {
        File(tempDir, "old.txt").writeText("data")
        val vm = FileManageViewModel(app, repo)
        vm.refresh()
        vm.renameFile("old.txt", "new.txt")
        dispatcher.scheduler.advanceUntilIdle()
        val files = vm.files.first()
        assertEquals(1, files.size)
        assertEquals("new.txt", files[0].name)
        assertFalse(File(tempDir, "old.txt").exists())
    }

    @Test
    fun `renameFile emits toast on duplicate`() = runTest(dispatcher) {
        File(tempDir, "a.txt").writeText("a")
        File(tempDir, "b.txt").writeText("b")
        val vm = FileManageViewModel(app, repo)
        vm.refresh()
        vm.renameFile("a.txt", "b.txt")
        assertEquals("文件名已存在", vm.toastEvent.first())
    }
}
