package com.assh.ai.tools

import com.assh.ai.ssh.ExecResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具层测试（C1）：每个 [Tool] 用 fake [ToolContext] 单测，无需真实 LLM/SSH/网络。
 * 同时验证 [ToolRegistry] 的规格列表与派发表同源、未知工具返回 null。
 */
class ToolTest {

    private class FakeContext(
        private val shell: ShellOutcome = ShellOutcome.Done(ExecResult("out", "", 0, false, false, 12)),
        private val fetchResult: String = "PAGE",
        private val searchResult: String = "RESULTS"
    ) : ToolContext {
        val notices = mutableListOf<String>()
        var lastShell: Triple<String, String?, Int>? = null
        override fun notice(text: String) { notices += text }
        override suspend fun runShell(command: String, why: String?, timeoutSec: Int): ShellOutcome {
            lastShell = Triple(command, why, timeoutSec); return shell
        }
        override suspend fun fetchUrl(url: String) = fetchResult
        override suspend fun search(query: String) = searchResult
    }

    @Test
    fun `registry exposes specs and dispatch from one source`() {
        val reg = ToolRegistry(listOf(RunCommandTool(), WebSearchTool(), FetchUrlTool(), FinishTool()))
        assertEquals(listOf("run_command", "web_search", "fetch_url", "finish"), reg.specs.map { it.name })
        assertTrue(reg.get("run_command") is RunCommandTool)
        assertNull("未知工具返回 null，不再静默落 else", reg.get("nope"))
    }

    @Test
    fun `run_command parses args and formats result`() = runBlocking {
        val ctx = FakeContext()
        val outcome = RunCommandTool().execute(
            """{"command":"ls -la","why":"看目录","timeout_sec":30}""", ctx
        )
        assertEquals(Triple("ls -la", "看目录", 30), ctx.lastShell)
        assertTrue(outcome is ToolOutcome.Continue)
        assertTrue((outcome as ToolOutcome.Continue).toolResult.contains("exit_code=0"))
    }

    @Test
    fun `run_command clamps timeout into range`() = runBlocking {
        val ctx = FakeContext()
        RunCommandTool().execute("""{"command":"x","timeout_sec":99999}""", ctx)
        assertEquals(1800, ctx.lastShell!!.third)
    }

    @Test
    fun `run_command blank command is ignored without touching shell`() = runBlocking {
        val ctx = FakeContext()
        val outcome = RunCommandTool().execute("""{"command":""}""", ctx)
        assertNull("空命令不应触达 SSH", ctx.lastShell)
        assertTrue(outcome is ToolOutcome.Continue)
    }

    @Test
    fun `run_command relays user rejection`() = runBlocking {
        val ctx = FakeContext(shell = ShellOutcome.Rejected)
        val outcome = RunCommandTool().execute("""{"command":"rm -rf /"}""", ctx)
        assertTrue((outcome as ToolOutcome.Continue).toolResult.contains("用户拒绝"))
    }

    @Test
    fun `fetch_url wraps result as untrusted data and emits notice`() = runBlocking {
        val ctx = FakeContext(fetchResult = "hello")
        val outcome = FetchUrlTool().execute("""{"url":"http://x"}""", ctx)
        val text = (outcome as ToolOutcome.Continue).toolResult
        assertTrue("应包裹不可信数据边界", text.contains("不可信的外部数据"))
        assertTrue(text.contains("hello"))
        assertTrue(ctx.notices.any { it.contains("http://x") })
    }

    @Test
    fun `web_search wraps result and emits notice`() = runBlocking {
        val ctx = FakeContext(searchResult = "r1 r2")
        val outcome = WebSearchTool().execute("""{"query":"kotlin"}""", ctx)
        val text = (outcome as ToolOutcome.Continue).toolResult
        assertTrue(text.contains("不可信的外部数据"))
        assertTrue(text.contains("r1 r2"))
    }

    @Test
    fun `finish returns Finish outcome with parsed fields`() = runBlocking {
        val outcome = FinishTool().execute("""{"success":true,"summary":"done"}""", FakeContext())
        assertTrue(outcome is ToolOutcome.Finish)
        outcome as ToolOutcome.Finish
        assertEquals(true, outcome.success)
        assertEquals("done", outcome.summary)
    }

    @Test
    fun `finish defaults on malformed json`() = runBlocking {
        val outcome = FinishTool().execute("not json", FakeContext()) as ToolOutcome.Finish
        assertEquals(false, outcome.success)
        assertEquals("任务结束。", outcome.summary)
    }
}
