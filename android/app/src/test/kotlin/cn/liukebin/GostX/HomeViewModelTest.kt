package cn.liukebin.GostX

import android.app.Application
import cn.liukebin.GostX.data.ConfigRepository
import cn.liukebin.GostX.data.DEFAULT_PROFILE_ID
import cn.liukebin.GostX.data.GlobalVpnState
import cn.liukebin.GostX.data.VpnState
import cn.liukebin.GostX.data.VpnStatus
import cn.liukebin.GostX.ui.home.HomeUiState
import cn.liukebin.GostX.ui.home.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
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

    private fun createViewModel() = HomeViewModel(mock<Application>(), repo)

    @Test
    fun `homeState reflects repository profiles and active id`() = runTest(dispatcher) {
        repo.addProfile("Second")
        repo.setActiveProfile("Second")

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(
            HomeUiState(profiles = repo.getProfiles(), activeProfileId = "Second"),
            viewModel.homeState.value
        )
    }

    @Test
    fun `setActiveProfile updates repository when vpn stopped`() = runTest(dispatcher) {
        repo.addProfile("Second")
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setActiveProfile("Second")

        assertEquals("Second", repo.getActiveProfileId())
    }

    @Test
    fun `setActiveProfile ignored while vpn connecting`() = runTest(dispatcher) {
        repo.addProfile("Second")
        GlobalVpnState.setConnecting()
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setActiveProfile("Second")

        assertEquals(DEFAULT_PROFILE_ID, repo.getActiveProfileId())
    }

    @Test
    fun `addProfile delegates to repository`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.addProfile("Second"))
        assertTrue(repo.getProfiles().any { it.id == "Second" })
    }

    @Test
    fun `homeState updates reactively when profile added after creation`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()
        val before = viewModel.homeState.value.profiles.size

        viewModel.addProfile("LateProfile")
        advanceUntilIdle()

        assertEquals(before + 1, viewModel.homeState.value.profiles.size)
        assertTrue(viewModel.homeState.value.profiles.any { it.id == "LateProfile" })
    }
}
