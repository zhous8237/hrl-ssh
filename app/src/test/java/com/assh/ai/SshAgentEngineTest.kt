package com.assh.ai

import com.assh.ai.llm.ChatMessage
import com.assh.ai.llm.LlmClient
import com.assh.ai.llm.LlmClientFactory
import com.assh.ai.llm.LlmConfig
import com.assh.ai.llm.LlmProvider
import com.assh.ai.llm.LlmResponse
import com.assh.ai.llm.StopReason
import com.assh.ai.llm.ToolCall
import com.assh.ai.ssh.ExecResult
import com.assh.ai.ssh.SshAgentRunner
import com.assh.ai.ssh.SshReconnectFailedException
import com.assh.data.db.dao.KnownHostDao
import com.assh.data.db.entity.AuthType
import com.assh.data.repo.HostRepository
import com.assh.service.readyForegroundSession
import com.assh.ssh.HostKeyChangedException
import com.assh.ssh.ResolvedHostConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SshAgentEngineTest {
    private class Fixture(scope: TestScope, val foreground: com.assh.service.ForegroundSession = readyForegroundSession()) {
        val dao = mockk<KnownHostDao>(relaxed = true)
        val runner = mockk<SshAgentRunner>(relaxed = true)
        val client = mockk<LlmClient>()
        val preferences = mockk<AgentPreferences>()
        val repository = mockk<HostRepository>()
        val factory = mockk<LlmClientFactory>()
        val history = mockk<AgentHistoryStore>(relaxed = true)
        val changed = HostKeyChangedException("host:22", "旧指纹", "新指纹", "ssh-rsa", byteArrayOf(1))
        val wrapped = IOException("传输错误", changed)
        var connected = false
        var chats = 0
        var commands = 0
        var reply: suspend (Int) -> LlmResponse = { step ->
            val call = ToolCall("call-$step", "run_command", """{"command":"pwd"}""")
            LlmResponse(null, listOf(call), StopReason.TOOL_USE, ChatMessage.assistant(null, listOf(call)))
        }
        val engine: SshAgentEngine

        init {
            val cfg = LlmConfig(LlmProvider.OPENAI, "https://unused.invalid", "测试", "测试", 0)
            coEvery { preferences.resolveActiveConfig() } returns cfg
            coEvery { preferences.confirmPolicyValue() } returns ConfirmPolicy.NEVER
            every { factory.forConfig(any()) } returns client
            coEvery { repository.resolveForConnect(any()) } returns
                ResolvedHostConfig(1, "测试主机", "host", 22, "root", AuthType.PASSWORD, "pw", null)
            every { runner.isConnected } answers { connected }
            coEvery { runner.connect(any()) } answers { connected = true }
            coEvery { runner.ensureConnected() } answers { connected = true }
            coEvery { runner.systemProbe() } returns "测试系统"
            coEvery { runner.runCommand(any(), any(), any(), any()) } answers {
                if (firstArg<String>() == "pwd") commands++
                ExecResult("输出", "", 0, false, false, 0)
            }
            coEvery { client.chat(any(), any(), any(), any()) } coAnswers { reply(++chats) }
            engine = SshAgentEngine(mockk(relaxed = true), repository, preferences, factory, dao, history,
                scope.backgroundScope, foregroundSession = foreground) { runner }
        }

        fun finish() {
            reply = { LlmResponse("完成", emptyList(), StopReason.END, ChatMessage.assistant("完成")) }
        }
    }

    @Test
    fun `首次调度前取消也归还前台准备租约`() = runTest {
        val f = Fixture(this)
        f.engine.start(1, "测试")
        f.engine.cancel()
        runCurrent()
        assertEquals(0, f.foreground.status.value.pendingOperations)
        assertFalse(f.foreground.status.value.active)
        assertEquals(0, f.chats)
    }

    @Test
    fun `后台服务尚未就绪时不能连接或执行任务`() = runTest {
        val session = com.assh.service.ForegroundSession { }
        val f = Fixture(this, session)
        f.finish()
        f.engine.start(1, "测试")
        runCurrent()
        coVerify(exactly = 0) { f.runner.connect(any()) }
        assertEquals(0, f.chats)
        session.markReady(session.status.value.generation)
        runCurrent()
        assertEquals(1, f.chats)
    }

    @Test
    fun `前台服务启动失败时不执行且错误可见`() = runTest {
        val f = Fixture(this, com.assh.service.ForegroundSession { throw IllegalStateException("系统禁止启动") })
        f.engine.start(1, "测试")
        runCurrent()
        assertEquals(AgentPhase.ERROR, f.engine.state.value.phase)
        assertTrue(f.engine.state.value.finishedMessage!!.contains("系统禁止启动"))
        coVerify(exactly = 0) { f.runner.connect(any()) }
        assertEquals(0, f.chats)
    }

    @Test
    fun `长任务超过十分钟仍推进而不是进入闲置释放`() = runTest {
        val f = Fixture(this)
        val original = f.reply
        f.reply = { step -> kotlinx.coroutines.delay(65_000); original(step) }
        f.engine.start(1, "长任务")
        runCurrent()
        advanceTimeBy(650_001)
        runCurrent()
        assertTrue(f.chats > 10)
        assertTrue(f.commands >= 10)
        assertEquals(AgentPhase.THINKING, f.engine.state.value.phase)
        assertTrue(f.foreground.status.value.active)
    }

    @Test
    fun `通知停止服务后继续重新取得保护且系统超时不重放`() = runTest {
        val f = Fixture(this)
        f.finish()
        f.engine.start(1, "测试")
        runCurrent()
        val old = f.foreground.status.value.generation
        f.engine.cancel()
        f.foreground.stop(old)
        f.engine.continueTask("继续")
        runCurrent()
        assertTrue(f.foreground.status.value.generation > old)
        assertEquals(2, f.chats)
        val generation = f.foreground.status.value.generation
        f.foreground.fail(generation, "系统后台时限")
        f.engine.onBackgroundUnavailable(generation, "系统后台时限")
        runCurrent()
        assertEquals(AgentPhase.ERROR, f.engine.state.value.phase)
        assertFalse(f.foreground.status.value.active)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, f.chats)
        coVerify(atLeast = 1) { f.history.upsert(any()) }
    }

    @Test
    fun `执行第26步并在200步结束且不执行第201步仍可追加`() = runTest {
        val f = Fixture(this)
        f.engine.start(1, "测试目标")
        runCurrent()
        assertEquals(200, f.chats)
        assertEquals(200, f.commands)
        assertEquals(200, f.engine.state.value.step)
        assertEquals(AgentPhase.AWAITING_FOLLOWUP, f.engine.state.value.phase)
        assertTrue(f.engine.state.value.finishedMessage!!.contains("200"))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(200, f.chats)
        f.finish()
        f.engine.continueTask("继续验证")
        runCurrent()
        assertEquals(201, f.chats)
        assertEquals(1, f.engine.state.value.step)
        assertTrue(f.engine.state.value.success)
    }

    @Test
    fun `提前完成后仍保持原有十分钟闲置释放`() = runTest {
        val f = Fixture(this)
        f.finish()
        f.engine.start(1, "测试")
        runCurrent()
        assertEquals(1, f.chats)
        advanceTimeBy(599_999)
        runCurrent()
        assertEquals(AgentPhase.AWAITING_FOLLOWUP, f.engine.state.value.phase)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(AgentPhase.DONE, f.engine.state.value.phase)
        assertTrue(f.engine.state.value.finishedMessage!!.contains("10 分钟"))
    }

    @Test
    fun `主动停止正在等待的模型调用后可以继续`() = runTest {
        val f = Fixture(this)
        f.reply = { CompletableDeferred<LlmResponse>().await() }
        f.engine.start(1, "测试")
        runCurrent()
        assertEquals(AgentPhase.THINKING, f.engine.state.value.phase)
        f.engine.cancel()
        runCurrent()
        assertEquals(AgentPhase.AWAITING_FOLLOWUP, f.engine.state.value.phase)
        f.finish()
        f.engine.continueTask("继续")
        runCurrent()
        assertTrue(f.engine.state.value.success)
    }

    @Test
    fun `首次连接包装指纹异常可进入设置且不会自动删除`() = runTest {
        val f = Fixture(this)
        coEvery { f.runner.connect(any()) } throws f.wrapped
        f.engine.start(1, "测试")
        runCurrent()
        assertEquals(AgentPhase.WAITING_HOSTKEY, f.engine.state.value.phase)
        assertEquals("host:22", f.engine.state.value.pendingHostKey?.hostPort)
        f.engine.prepareHostKeySettings()
        runCurrent()
        assertNull(f.engine.state.value.pendingHostKey)
        assertEquals(AgentPhase.AWAITING_FOLLOWUP, f.engine.state.value.phase)
        coVerify(exactly = 0) { f.dao.delete(any()) }
        coVerify(exactly = 0) { f.dao.clear() }
        f.finish()
        f.engine.continueTask("")
        runCurrent()
        assertTrue(f.engine.state.value.success)
    }

    @Test
    fun `历史恢复也识别包装指纹异常且拒绝会结束连接`() = runTest {
        val f = Fixture(this)
        coEvery { f.runner.connect(any()) } throws f.wrapped
        f.engine.resume(AgentRunRecord("历史", "测试", "目标", 0, 0, true, "完成", emptyList(), 1))
        runCurrent()
        assertEquals(AgentPhase.WAITING_HOSTKEY, f.engine.state.value.phase)
        f.engine.rejectHostKey()
        runCurrent()
        assertEquals(AgentPhase.ERROR, f.engine.state.value.phase)
        assertNull(f.engine.state.value.pendingHostKey)
    }

    @Test
    fun `确认信任只删除当前指纹并继续首次连接`() = runTest {
        val f = Fixture(this)
        var attempts = 0
        coEvery { f.runner.connect(any()) } answers {
            if (attempts++ == 0) throw f.wrapped
            f.connected = true
        }
        f.finish()
        f.engine.start(1, "测试")
        runCurrent()
        f.engine.trustHostKey()
        runCurrent()
        assertTrue(f.engine.state.value.success)
        assertEquals(2, attempts)
        coVerify(exactly = 1) { f.dao.delete("host:22") }
        coVerify(exactly = 0) { f.dao.clear() }
    }

    @Test
    fun `手动重连指纹错误不被吞成普通掉线`() = runTest {
        val f = Fixture(this)
        f.finish()
        f.engine.start(1, "测试")
        runCurrent()
        f.connected = false
        coEvery { f.runner.ensureConnected() } throws f.wrapped
        f.engine.continueTask("继续")
        runCurrent()
        assertEquals(AgentPhase.WAITING_HOSTKEY, f.engine.state.value.phase)
        coVerify(exactly = 0) { f.runner.tryReconnect() }
        f.engine.prepareHostKeySettings()
        runCurrent()
        coEvery { f.runner.ensureConnected() } answers { f.connected = true }
        f.engine.continueTask("继续")
        runCurrent()
        assertTrue(f.engine.state.value.success)
    }

    @Test
    fun `后台重连指纹错误等待处理而非循环重试且信任不重放命令`() = runTest {
        val f = Fixture(this)
        coEvery { f.runner.runCommand("pwd", any(), any(), any()) } answers {
            f.connected = false
            throw SshReconnectFailedException()
        }
        coEvery { f.runner.tryReconnect() } throws f.wrapped
        f.engine.start(1, "测试")
        runCurrent()
        assertEquals(AgentPhase.WAITING_HOSTKEY, f.engine.state.value.phase)
        advanceTimeBy(60_000)
        runCurrent()
        coVerify(exactly = 1) { f.runner.tryReconnect() }
        f.engine.trustHostKey()
        runCurrent()
        assertEquals(AgentPhase.AWAITING_FOLLOWUP, f.engine.state.value.phase)
        assertFalse(f.engine.state.value.disconnected)
        assertEquals(1, f.chats)
        coVerify(exactly = 1) { f.runner.runCommand("pwd", any(), any(), any()) }
    }

    @Test
    fun `网络及密码错误不显示指纹弹窗`() = runTest {
        for (error in listOf(IOException("连接重置"), IllegalStateException("密码未提供"))) {
            val f = Fixture(this)
            coEvery { f.runner.connect(any()) } throws error
            f.engine.start(1, "测试")
            runCurrent()
            assertEquals(AgentPhase.ERROR, f.engine.state.value.phase)
            assertNull(f.engine.state.value.pendingHostKey)
        }
    }
}
