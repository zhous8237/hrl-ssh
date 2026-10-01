package com.assh.service

import android.os.PowerManager
import com.assh.ai.AgentPhase
import com.assh.ai.AgentState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExecutionWakeLockTest {
    private class LockFixture {
        var held = false
        val lock = mockk<PowerManager.WakeLock>(relaxed = true)
        init {
            every { lock.acquire(any()) } answers { held = true }
            every { lock.isHeld } answers { held }
            every { lock.release() } answers { held = false }
        }
    }

    @Test
    fun `跨十分钟持续续租且重复更新不重复创建计时`() = runTest {
        val fixture = LockFixture()
        val wake = ExecutionWakeLock(backgroundScope, fixture.lock) { fail(it.message) }
        wake.update(true)
        wake.update(true)
        runCurrent()
        advanceTimeBy(600_001)
        runCurrent()
        assertTrue(fixture.held)
        verify(exactly = 11) { fixture.lock.acquire(120_000) }
        wake.update(false)
        wake.close()
        assertFalse(fixture.held)
        advanceTimeBy(300_000)
        runCurrent()
        verify(exactly = 11) { fixture.lock.acquire(any()) }
        verify(exactly = 1) { fixture.lock.release() }
    }

    @Test
    fun `两个服务独立释放不能互相撤销保护`() = runTest {
        val first = LockFixture()
        val second = LockFixture()
        val a = ExecutionWakeLock(backgroundScope, first.lock) { fail(it.message) }
        val b = ExecutionWakeLock(backgroundScope, second.lock) { fail(it.message) }
        a.update(true)
        b.update(true)
        a.close()
        assertFalse(first.held)
        assertTrue(second.held)
        b.close()
        assertFalse(second.held)
    }

    @Test
    fun `续租失败立即释放并反馈而不是静默失去保护`() = runTest {
        val fixture = LockFixture()
        var failures = 0
        val wake = ExecutionWakeLock(backgroundScope, fixture.lock) { failures++ }
        wake.update(true)
        runCurrent()
        every { fixture.lock.acquire(any()) } throws SecurityException("系统拒绝")
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(1, failures)
        assertFalse(fixture.held)
    }

    @Test
    fun `等待确认与追加释放CPU实际后台重连才持锁`() {
        for (phase in listOf(AgentPhase.CONNECTING, AgentPhase.THINKING, AgentPhase.EXECUTING)) {
            assertTrue(AgentState(phase = phase).foregroundWork().cpu)
        }
        for (phase in listOf(AgentPhase.WAITING_CONFIRM, AgentPhase.WAITING_HOSTKEY, AgentPhase.AWAITING_FOLLOWUP, AgentPhase.DONE, AgentPhase.ERROR)) {
            assertFalse(AgentState(phase = phase).foregroundWork().cpu)
        }
        assertFalse(AgentState(phase = AgentPhase.AWAITING_FOLLOWUP, disconnected = true).foregroundWork().cpu)
        assertTrue(AgentState(phase = AgentPhase.AWAITING_FOLLOWUP, disconnected = true, reconnecting = true).foregroundWork().cpu)
        assertFalse(AgentState(phase = AgentPhase.WAITING_HOSTKEY, reconnecting = true).foregroundWork().cpu)
    }
}
