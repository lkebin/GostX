package cn.liukebin.gostx

import cn.liukebin.gostx.data.ConfigRepository
import cn.liukebin.gostx.data.GlobalVpnState
import cn.liukebin.gostx.data.VpnState
import cn.liukebin.gostx.data.VpnStatus
import cn.liukebin.gostx.ui.config.ConfigViewModel
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

    private fun firstProfileId(): String = repo.addProfile("First")!!

    @Test
    fun `canDelete trueWhenOneProfileAndStopped`() = runTest(dispatcher) {
        val profileId = firstProfileId()
        GlobalVpnState.setStopped()
        val viewModel = ConfigViewModel(repo, profileId)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.canDelete)
    }

    @Test
    fun `canDelete falseWhenConnecting`() = runTest(dispatcher) {
        val profileId = firstProfileId()
        GlobalVpnState.setConnecting()
        val viewModel = ConfigViewModel(repo, profileId)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.canDelete)
    }

    @Test
    fun `canDelete reactsToVpnStateChange`() = runTest(dispatcher) {
        val profileId = firstProfileId()
        GlobalVpnState.setConnecting()
        val viewModel = ConfigViewModel(repo, profileId)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canDelete)

        GlobalVpnState.setStopped()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.canDelete)
    }

    @Test
    fun `deleteProfile noOpWhenCanDeleteFalse`() = runTest(dispatcher) {
        val profileId = firstProfileId()
        GlobalVpnState.setConnecting()
        val viewModel = ConfigViewModel(repo, profileId)
        val navBackEvents = mutableListOf<Unit>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.navBack.collect { navBackEvents.add(it) }
        }
        advanceUntilIdle()

        viewModel.deleteProfile()
        advanceUntilIdle()

        assertEquals(1, repo.getProfiles().size)
        assertTrue(navBackEvents.isEmpty())
        job.cancel()
    }

    @Test
    fun `deleteProfile emitsNavBackOnSuccess`() = runTest(dispatcher) {
        val profileId = firstProfileId()
        GlobalVpnState.setStopped()
        val viewModel = ConfigViewModel(repo, profileId)
        val navBackEvents = mutableListOf<Unit>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.navBack.collect { navBackEvents.add(it) }
        }
        advanceUntilIdle()

        viewModel.deleteProfile()
        advanceUntilIdle()

        assertEquals(0, repo.getProfiles().size)
        assertEquals(1, navBackEvents.size)
        job.cancel()
    }
}
