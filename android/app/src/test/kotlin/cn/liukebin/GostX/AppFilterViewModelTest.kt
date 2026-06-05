package cn.liukebin.gostx

import cn.liukebin.gostx.data.AppFilterMode
import cn.liukebin.gostx.data.ConfigRepository
import cn.liukebin.gostx.ui.settings.AppFilterUiState
import cn.liukebin.gostx.ui.settings.AppFilterViewModel
import cn.liukebin.gostx.ui.settings.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppFilterViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var repo: ConfigRepository

    private val fakeApps = listOf(
        InstalledApp("com.a", "App A", hasLauncher = true),
        InstalledApp("com.b", "App B", hasLauncher = true),
        InstalledApp("com.c", "Zcustom", hasLauncher = false)
    )

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        prefs = FakeSharedPreferences()
        repo = ConfigRepository(prefs)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildVm(
        extra: ConfigRepository.() -> Unit = {}
    ): AppFilterViewModel {
        repo.extra()
        return AppFilterViewModel(repo) { fakeApps }
    }

    // ── AppFilterUiState pure logic ──────────────────────────────────────

    @Test fun `filtered returns all apps when query is blank`() {
        val state = AppFilterUiState(apps = fakeApps, query = "", showAll = true)
        assertEquals(fakeApps, state.filtered)
    }

    @Test fun `filtered returns only launcher apps by default`() {
        val state = AppFilterUiState(apps = fakeApps, query = "")
        assertEquals(
            listOf(InstalledApp("com.a", "App A", true), InstalledApp("com.b", "App B", true)),
            state.filtered
        )
    }

    @Test fun `filtered includes non-launcher apps when showAll is true`() {
        val state = AppFilterUiState(apps = fakeApps, query = "", showAll = true)
        assertTrue(state.filtered.any { it.packageName == "com.c" })
    }

    @Test fun `filtered is case-insensitive on label`() {
        val state = AppFilterUiState(apps = fakeApps, query = "app", showAll = true)
        assertEquals(listOf(InstalledApp("com.a", "App A", true), InstalledApp("com.b", "App B", true)), state.filtered)
    }

    @Test fun `filtered returns empty when no match`() {
        val state = AppFilterUiState(apps = fakeApps, query = "xyz", showAll = true)
        assertTrue(state.filtered.isEmpty())
    }

    @Test fun `canSave is true in blacklist mode with empty selection`() {
        val state = AppFilterUiState(isWhitelistMode = false, selected = emptySet())
        assertTrue(state.canSave)
    }

    @Test fun `canSave is false in whitelist mode with empty selection`() {
        val state = AppFilterUiState(isWhitelistMode = true, selected = emptySet())
        assertFalse(state.canSave)
    }

    @Test fun `canSave is true in whitelist mode with non-empty selection`() {
        val state = AppFilterUiState(isWhitelistMode = true, selected = setOf("com.a"))
        assertTrue(state.canSave)
    }

    // ── ViewModel behaviour ──────────────────────────────────────────────

    @Test fun `uiState starts loading and reflects repo selection`() = runTest(dispatcher) {
        repo.appFilterList = setOf("com.a")
        val vm = buildVm()
        assertTrue(vm.uiState.value.isLoading)
        assertEquals(setOf("com.a"), vm.uiState.value.selected)
    }

    @Test fun `apps load after idle`() = runTest(dispatcher) {
        val vm = buildVm()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(fakeApps, vm.uiState.value.apps)
    }

    @Test fun `isWhitelistMode reflects repo mode at init`() = runTest(dispatcher) {
        repo.appFilterMode = AppFilterMode.WHITELIST
        val vm = buildVm()
        assertTrue(vm.uiState.value.isWhitelistMode)
    }

    @Test fun `toggleApp adds unselected package to selected`() = runTest(dispatcher) {
        val vm = buildVm()
        vm.toggleApp("com.a")
        assertTrue("com.a" in vm.uiState.value.selected)
    }

    @Test fun `toggleApp removes already-selected package`() = runTest(dispatcher) {
        val vm = buildVm { appFilterList = setOf("com.a") }
        vm.toggleApp("com.a")
        assertFalse("com.a" in vm.uiState.value.selected)
    }

    @Test fun `setQuery updates query in state`() = runTest(dispatcher) {
        val vm = buildVm()
        vm.setQuery("App")
        assertEquals("App", vm.uiState.value.query)
    }

    @Test fun `save persists selected list to repo`() = runTest(dispatcher) {
        val vm = buildVm()
        vm.toggleApp("com.a")
        vm.toggleApp("com.b")
        vm.save()
        assertEquals(setOf("com.a", "com.b"), repo.appFilterList)
    }

    @Test fun `save does nothing when canSave is false`() = runTest(dispatcher) {
        repo.appFilterMode = AppFilterMode.WHITELIST
        val vm = buildVm()
        vm.save()
        assertTrue(repo.appFilterList.isEmpty())
    }

    @Test fun `toggleShowAll flips showAll state`() = runTest(dispatcher) {
        val vm = buildVm()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showAll)
        vm.toggleShowAll()
        assertTrue(vm.uiState.value.showAll)
        vm.toggleShowAll()
        assertFalse(vm.uiState.value.showAll)
    }

    @Test fun `loading failure clears spinner and returns empty list`() = runTest(dispatcher) {
        val vm = AppFilterViewModel(repo) { error("simulated failure") }
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.apps.isEmpty())
    }
}
