package cn.liukebin.GostX

import cn.liukebin.GostX.data.ConfigRepository
import cn.liukebin.GostX.data.DEFAULT_PROFILE_ID
import cn.liukebin.GostX.data.GlobalVpnState
import cn.liukebin.GostX.data.VpnState
import cn.liukebin.GostX.data.VpnStatus
import cn.liukebin.GostX.ui.config.ConfigViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: TestDispatcher
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var repo: ConfigRepository

    @Before
    fun setup() {
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
        prefs = FakeSharedPreferences()
        repo = ConfigRepository(prefs)
        GlobalVpnState.setState(VpnState(VpnStatus.STOPPED))
    }

    @After
    fun tearDown() {
        GlobalVpnState.setState(VpnState(VpnStatus.STOPPED))
        Dispatchers.resetMain()
    }

    @Test
    fun `canDelete falseWhenOneProfile`() = runTest(dispatcher) {
        GlobalVpnState.setStopped()

        val viewModel = ConfigViewModel(repo, DEFAULT_PROFILE_ID)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canDelete)
    }

    @Test
    fun `canDelete trueWhenMultipleProfilesAndStopped`() = runTest(dispatcher) {
        repo.addProfile("Second")
        GlobalVpnState.setStopped()

        val viewModel = ConfigViewModel(repo, DEFAULT_PROFILE_ID)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.canDelete)
    }

    @Test
    fun `canDelete falseWhenConnecting`() = runTest(dispatcher) {
        repo.addProfile("Second")
        GlobalVpnState.setConnecting()

        val viewModel = ConfigViewModel(repo, DEFAULT_PROFILE_ID)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canDelete)
    }

    @Test
    fun `canDelete reactsToVpnStateChange`() = runTest(dispatcher) {
        repo.addProfile("Second")
        GlobalVpnState.setConnecting()
        val viewModel = ConfigViewModel(repo, DEFAULT_PROFILE_ID)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canDelete)

        GlobalVpnState.setStopped()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.canDelete)
    }

    @Test
    fun `canDelete reactsToProfileListChange`() = runTest(dispatcher) {
        repo.addProfile("Second")
        GlobalVpnState.setStopped()
        val viewModel = ConfigViewModel(repo, DEFAULT_PROFILE_ID)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.canDelete)

        repo.deleteProfile("Second")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canDelete)
    }

    @Test
    fun `deleteProfile noOpWhenCanDeleteFalse`() = runTest(dispatcher) {
        repo.addProfile("Second")
        GlobalVpnState.setConnecting()
        val viewModel = ConfigViewModel(repo, DEFAULT_PROFILE_ID)
        val navBackEvents = mutableListOf<Unit>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.navBack.collect { navBackEvents.add(it) }
        }
        advanceUntilIdle()

        viewModel.deleteProfile()
        advanceUntilIdle()

        assertEquals(listOf(DEFAULT_PROFILE_ID, "Second"), repo.getProfiles().map { it.id })
        assertTrue(navBackEvents.isEmpty())
        job.cancel()
    }

    @Test
    fun `deleteProfile emitsNavBackOnSuccess`() = runTest(dispatcher) {
        repo.addProfile("Second")
        GlobalVpnState.setStopped()
        val viewModel = ConfigViewModel(repo, DEFAULT_PROFILE_ID)
        val navBackEvents = mutableListOf<Unit>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.navBack.collect { navBackEvents.add(it) }
        }
        advanceUntilIdle()

        viewModel.deleteProfile()
        advanceUntilIdle()

        assertEquals(listOf("Second"), repo.getProfiles().map { it.id })
        assertEquals(1, navBackEvents.size)
        job.cancel()
    }
}
