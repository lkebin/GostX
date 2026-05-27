package com.gostx

import com.gostx.data.VpnState
import com.gostx.data.VpnStateRepository
import com.gostx.data.VpnStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VpnStateRepositoryTest {
    @Test fun `initial state is STOPPED`() = runBlocking {
        val repo = VpnStateRepository()
        assertEquals(VpnStatus.STOPPED, repo.state.first().status)
    }

    @Test fun `setState updates flow`() = runBlocking {
        val repo = VpnStateRepository()
        repo.setState(VpnState(VpnStatus.CONNECTED, "127.0.0.1:10808"))
        val s = repo.state.first()
        assertEquals(VpnStatus.CONNECTED, s.status)
        assertEquals("127.0.0.1:10808", s.listenAddr)
    }

    @Test fun `setError stores message`() = runBlocking {
        val repo = VpnStateRepository()
        repo.setError("connection refused")
        assertEquals("connection refused", repo.state.first().error)
    }
}
