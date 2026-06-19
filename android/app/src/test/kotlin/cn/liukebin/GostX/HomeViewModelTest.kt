package cn.liukebin.gostx

import android.app.Application
import cn.liukebin.gostx.data.ConfigRepository
import cn.liukebin.gostx.data.GlobalVpnState
import cn.liukebin.gostx.data.VpnState
import cn.liukebin.gostx.data.VpnStatus
import cn.liukebin.gostx.ui.home.HomeUiState
import cn.liukebin.gostx.ui.home.HomeViewModel
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
        val secondId = repo.addProfile("Second")!!
        repo.setActiveProfile(secondId)

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(
            HomeUiState(profiles = repo.getProfiles(), activeProfileId = secondId),
            viewModel.homeState.value
        )
    }

    @Test
    fun `setActiveProfile updates repository when vpn stopped`() = runTest(dispatcher) {
        val secondId = repo.addProfile("Second")!!
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setActiveProfile(secondId)

        assertEquals(secondId, repo.getActiveProfileId())
    }

    @Test
    fun `setActiveProfile ignored while vpn connecting`() = runTest(dispatcher) {
        repo.addProfile("First")
        val secondId = repo.addProfile("Second")!!
        GlobalVpnState.setConnecting()
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setActiveProfile(secondId)

        // setActiveProfile is ignored while connecting, active stays on First
        assertEquals(repo.getProfiles().first().id, repo.getActiveProfileId())
    }

    @Test
    fun `addProfile delegates to repository`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val newId = viewModel.addProfile("Second")
        assertTrue(newId != null)
        assertTrue(repo.getProfiles().any { it.name == "Second" })
    }

    @Test
    fun `homeState updates reactively when profile added after creation`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()
        val before = viewModel.homeState.value.profiles.size

        viewModel.addProfile("LateProfile")
        advanceUntilIdle()

        assertEquals(before + 1, viewModel.homeState.value.profiles.size)
        assertTrue(viewModel.homeState.value.profiles.any { it.name == "LateProfile" })
    }

    @Test
    fun `homeState reflects renamed profile`() = runTest(dispatcher) {
        val secondId = repo.addProfile("Second")!!
        val viewModel = createViewModel()
        advanceUntilIdle()

        repo.renameProfile(secondId, "Renamed")
        advanceUntilIdle()

        assertEquals("Renamed", viewModel.homeState.value.profiles.find { it.id == secondId }?.name)
    }
}
