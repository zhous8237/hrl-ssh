package com.assh.ai.ssh

import com.assh.data.db.dao.KnownHostDao
import com.assh.data.db.entity.AuthType
import com.assh.ssh.ResolvedHostConfig
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException

/**
 * [SshAgentRunner] 退避重连测试：注入 [StandardTestDispatcher] 让 `delay` 走虚拟时钟、
 * 注入脚本化 [AgentSshClientFactory] 控制每次建连结果——无需真 socket、不真等。
 * 经公开的 [SshAgentRunner.ensureConnected] 间接驱动私有的 reconnectWithBackoff。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectTest {

    private fun cfg() = ResolvedHostConfig(
        hostId = 1, label = "h", host = "10.0.0.1", port = 22,
        username = "root", authType = AuthType.PASSWORD, password = "pw", privateKeyPem = null
    )

    private class FakeAgentSshClient(override val isConnected: Boolean) : AgentSshClient {
        override fun startSession(): Session = throw UnsupportedOperationException("不在重连测试范围")
        override fun disconnect() {}
    }

    /** 每次 connect() 依序执行一个行为（返回 client 或抛异常）；耗尽后默认持续瞬时失败。 */
    private class ScriptedFactory(behaviors: List<() -> AgentSshClient>) : AgentSshClientFactory {
        private val q = ArrayDeque(behaviors)
        var calls = 0; private set
        override suspend fun connect(cfg: ResolvedHostConfig): AgentSshClient {
            calls++
            return (q.removeFirstOrNull() ?: { throw IOException("connection reset") }).invoke()
        }
    }

    private fun runner(factory: AgentSshClientFactory, backoff: Backoff, scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) =
        SshAgentRunner(mockk<KnownHostDao>(relaxed = true), factory, backoff, StandardTestDispatcher(scheduler))

    @Test
    fun `ensureConnected retries transient failures then succeeds`() = runTest {
        val f = ScriptedFactory(listOf(
            { FakeAgentSshClient(false) },              // 初次 connect → 视为已断
            { throw IOException("connection reset") },  // 重连 1 失败（瞬时）
            { throw IOException("broken pipe") },       // 重连 2 失败
            { FakeAgentSshClient(true) }                // 重连 3 成功
        ))
        val r = runner(f, Backoff(baseMs = 1_000, maxMs = 15_000, random = { it }), testScheduler)
        r.connect(cfg())
        r.ensureConnected()
        assertTrue("最终应连上", r.isConnected)
        assertEquals("1 次初连 + 3 次重连", 4, f.calls)
    }

    @Test
    fun `ensureConnected gives up after budget on persistent transient failures`() = runTest {
        val behaviors = listOf<() -> AgentSshClient>({ FakeAgentSshClient(false) }) +
            List(1000) { { throw IOException("connection reset") } }
        val f = ScriptedFactory(behaviors)
        val r = runner(f, Backoff(baseMs = 1_000, maxMs = 5_000, random = { it }), testScheduler)
        r.connect(cfg())
        val ex = runCatching { r.ensureConnected() }.exceptionOrNull()
        assertTrue("预算耗尽应抛 SshReconnectFailedException，实为 $ex", ex is SshReconnectFailedException)
        assertFalse(r.isConnected)
        assertTrue("应进行了多次重连尝试", f.calls > 3)
    }

    @Test
    fun `ensureConnected aborts immediately on fatal error`() = runTest {
        val f = ScriptedFactory(listOf(
            { FakeAgentSshClient(false) },
            { throw RuntimeException("Auth fail") }   // 致命，不应重试
        ))
        val r = runner(f, Backoff(random = { it }), testScheduler)
        r.connect(cfg())
        val ex = runCatching { r.ensureConnected() }.exceptionOrNull()
        assertTrue("致命错应原样抛出，实为 $ex", ex?.message?.contains("Auth fail") == true)
        assertEquals("1 次初连 + 1 次致命重连（无重试）", 2, f.calls)
    }

    @Test
    fun `hasTimeoutCause distinguishes timeout from disconnect`() {
        assertTrue(hasTimeoutCause(java.util.concurrent.TimeoutException("t")))
        assertTrue(hasTimeoutCause(IOException("wrap", java.util.concurrent.TimeoutException())))
        assertFalse(hasTimeoutCause(IOException("connection reset")))
        assertFalse(hasTimeoutCause(EOFException("server closed connection")))
    }
}
