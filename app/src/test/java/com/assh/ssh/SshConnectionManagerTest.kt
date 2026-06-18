package com.assh.ssh

import com.assh.data.db.entity.AuthType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SshConnectionManagerTest {

    private fun cfg(hostId: Long) = ResolvedHostConfig(
        hostId = hostId,
        label = "h$hostId",
        host = "10.0.0.$hostId",
        port = 22,
        username = "root",
        authType = AuthType.PASSWORD,
        password = "pw",
        privateKeyPem = null
    )

    /** 记录工厂创建的每个 fake，并允许预置失败 */
    private class RecordingFactory(
        val failHosts: Set<Long> = emptySet()
    ) : SshTransportFactory {
        val created = mutableListOf<FakeSshTransport>()
        override fun create(hostId: Long): SshTransport =
            FakeSshTransport(hostId, if (hostId in failHosts) RuntimeException("boom") else null)
                .also { created += it }
    }

    private fun manager(
        factory: SshTransportFactory,
        scope: CoroutineScope
    ) = SshConnectionManager(factory, scope)

    @Test
    fun `connect creates and returns a connected transport`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val factory = RecordingFactory()
        val mgr = manager(factory, scope)

        val t = mgr.connect(cfg(1))

        assertEquals(ConnState.CONNECTED, t.state.value)
        assertSame(t, mgr.get(1))
        assertEquals(1, factory.created.size)
        assertEquals(1, mgr.activeCount)
    }

    @Test
    fun `connect dedupes when already connected`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val factory = RecordingFactory()
        val mgr = manager(factory, scope)

        val first = mgr.connect(cfg(1))
        val second = mgr.connect(cfg(1))

        assertSame("已连接的 host 再次 connect 应复用同一会话", first, second)
        assertEquals("不应创建第二个 transport", 1, factory.created.size)
        assertEquals(1, (first as FakeSshTransport).connectCount)
    }

    @Test
    fun `disconnect closes by user and drops cached config`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val factory = RecordingFactory()
        val mgr = manager(factory, scope)

        mgr.connect(cfg(1))
        val t = factory.created.first()
        mgr.disconnect(1)

        assertEquals(1, t.closeCount)
        assertEquals(true, t.lastCloseByUser)
        assertNull("disconnect 必须清掉重连配置", mgr.cachedConfig(1))
        assertNull(mgr.get(1))
    }

    @Test
    fun `cleanupDisconnected preserves cached config for one-tap reconnect`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val factory = RecordingFactory()
        val mgr = manager(factory, scope)

        mgr.connect(cfg(1))
        val t = factory.created.first()
        mgr.cleanupDisconnected(1)

        assertEquals("意外断线不应重复 close", 0, t.closeCount)
        assertNotNull("cleanupDisconnected 必须保留配置以便免密重连", mgr.cachedConfig(1))
        assertNull(mgr.get(1))
    }

    @Test
    fun `reconnect reuses cached config and creates a fresh transport`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val factory = RecordingFactory()
        val mgr = manager(factory, scope)

        mgr.connect(cfg(1))
        val reconnected = mgr.reconnect(1)

        assertEquals(ConnState.CONNECTED, reconnected.state.value)
        assertEquals("reconnect 应新建一个 transport", 2, factory.created.size)
        assertSame(reconnected, mgr.get(1))
    }

    @Test
    fun `reconnect without cached config throws`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val mgr = manager(RecordingFactory(), scope)

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { mgr.reconnect(99) }
        }
    }

    @Test
    fun `states flow aggregates per-host connection state`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val factory = RecordingFactory()
        val mgr = manager(factory, scope)

        mgr.connect(cfg(1))
        mgr.connect(cfg(2))

        assertEquals(ConnState.CONNECTED, mgr.states.value[1])
        assertEquals(ConnState.CONNECTED, mgr.states.value[2])
        assertEquals(2, mgr.activeCount)

        mgr.disconnect(1)
        assertNull("断开后聚合 map 应移除该 host", mgr.states.value[1])
        assertEquals(1, mgr.activeCount)
    }

    @Test
    fun `failed connect surfaces error state`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val factory = RecordingFactory(failHosts = setOf(1))
        val mgr = manager(factory, scope)

        assertThrows(RuntimeException::class.java) {
            kotlinx.coroutines.runBlocking { mgr.connect(cfg(1)) }
        }
        assertTrue(factory.created.first().state.value == ConnState.ERROR)
        assertEquals("失败连接不计入活跃数", 0, mgr.activeCount)
    }
}
