package com.assh.service

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ForegroundSessionTest {
    @Test
    fun `只有服务确认进入前台后才允许执行`() = runTest {
        var starts = 0
        val session = ForegroundSession { starts++ }
        val first = session.request()
        val second = session.request()
        val waiting = async { first.awaitReady() }
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertEquals(1, starts)
        assertEquals(2, session.status.value.pendingOperations)
        assertTrue(session.markReady(first.generation))
        waiting.await()
        second.awaitReady()
        first.close()
        first.close()
        second.close()
        assertEquals(0, session.status.value.pendingOperations)
        assertTrue(session.status.value.active)
    }

    @Test
    fun `服务启动失败不伪称已保护且可以手动重试`() = runTest {
        var deny = true
        val session = ForegroundSession { if (deny) throw IllegalStateException("系统拒绝") }
        val failed = session.request()
        assertTrue(runCatching { failed.awaitReady() }.exceptionOrNull() is ForegroundUnavailableException)
        assertFalse(session.status.value.active)
        assertTrue(session.status.value.error!!.contains("系统拒绝"))
        failed.close()
        deny = false
        val retried = session.request()
        assertNotEquals(failed.generation, retried.generation)
        session.markReady(retried.generation)
        retried.awaitReady()
        retried.close()
        assertNull(session.status.value.error)
    }

    @Test
    fun `旧服务回调和旧租约不能停止新一代服务`() = runTest {
        val session = ForegroundSession { }
        val old = session.request()
        session.stop(old.generation)
        val current = session.request()
        assertFalse(session.markReady(old.generation))
        assertFalse(session.fail(old.generation, "旧错误"))
        old.close()
        session.stop(old.generation)
        assertEquals(1, session.status.value.pendingOperations)
        session.markReady(current.generation)
        current.awaitReady()
        assertTrue(session.isActive(current.generation))
        current.close()
    }

    @Test
    fun `服务不响应时有限等待且超时后不执行`() = runTest {
        val session = ForegroundSession { }
        val lease = session.request()
        val result = async { runCatching { lease.awaitReady() }.exceptionOrNull() }
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        assertTrue(result.await() is ForegroundUnavailableException)
        assertFalse(session.markReady(lease.generation))
        assertFalse(session.status.value.active)
        lease.close()
    }

    @Test
    fun `停止后继续会话会重新申请服务`() = runTest {
        var starts = 0
        lateinit var session: ForegroundSession
        session = ForegroundSession { starts++; session.markReady(it) }
        val first = session.request()
        first.awaitReady()
        first.close()
        session.stop(first.generation)
        val second = session.request()
        second.awaitReady()
        assertEquals(2, starts)
        assertTrue(session.isActive(second.generation))
        second.close()
    }
}
