package cn.liukebin.gostx

import cn.liukebin.gostx.service.HEALTH_RESTART_DELAYS_MS
import cn.liukebin.gostx.service.HealthAction
import cn.liukebin.gostx.service.HealthRetryBudget
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthRetryBudgetTest {

    @Test
    fun `first failed probe asks for a restart`() {
        assertEquals(HealthAction.RESTART, HealthRetryBudget().onProbeFailed())
    }

    @Test
    fun `failed probes are restarted up to the budget then reported`() {
        val budget = HealthRetryBudget(maxRestarts = 2)
        assertEquals(HealthAction.RESTART, budget.onProbeFailed())
        assertEquals(HealthAction.RESTART, budget.onProbeFailed())
        assertEquals(HealthAction.GIVE_UP, budget.onProbeFailed())
        assertEquals(HealthAction.GIVE_UP, budget.onProbeFailed())
    }

    @Test
    fun `restart backoff grows with each attempt`() {
        val budget = HealthRetryBudget(maxRestarts = 2)
        budget.onProbeFailed()
        assertEquals(HEALTH_RESTART_DELAYS_MS[0], budget.restartDelayMs)
        budget.onProbeFailed()
        assertEquals(HEALTH_RESTART_DELAYS_MS[1], budget.restartDelayMs)
    }

    // Regression: the retry budget used to be a boolean that was only cleared
    // by a manual stop. One dead episode hours earlier therefore made every
    // later failed probe give up immediately ("stale state suspected") instead
    // of restarting the VPN — a restart that would have fixed it.
    @Test
    fun `a healthy probe restores the budget for the next episode`() {
        val budget = HealthRetryBudget(maxRestarts = 2)
        budget.onProbeFailed()
        budget.onProbeFailed()
        assertEquals(HealthAction.GIVE_UP, budget.onProbeFailed())

        budget.reset()

        assertEquals(HealthAction.RESTART, budget.onProbeFailed())
    }

    @Test
    fun `attempt counter is observable for logging`() {
        val budget = HealthRetryBudget(maxRestarts = 2)
        assertEquals(0, budget.restartsUsed)
        budget.onProbeFailed()
        assertEquals(1, budget.restartsUsed)
    }
}