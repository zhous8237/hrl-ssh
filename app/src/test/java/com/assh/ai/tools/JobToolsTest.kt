package com.assh.ai.tools

import com.assh.ai.ssh.ExecResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台任务工具测试：用 fake [ToolContext] 记录 runDetachedJob/runReadonly 的入参，
 * 断言 setsid 包装串结构、单引号转义、tail 偏移、哨兵解析、进程组终止命令。
 */
class JobToolsTest {

    private class FakeJobContext(
        private val rejectDetached: Boolean = false,
        private val readonlyOutcome: ShellOutcome? = null
    ) : ToolContext {
        var detached: Triple<String, String?, String>? = null   // innerCommand, why, wrappedCommand
        var readonly: Pair<String, Int>? = null                 // command, timeoutSec
        override fun notice(text: String) {}
        override suspend fun runShell(command: String, why: String?, timeoutSec: Int) = ShellOutcome.ConnectionClosed
        override suspend fun runDetachedJob(innerCommand: String, why: String?, wrappedCommand: String): ShellOutcome {
            detached = Triple(innerCommand, why, wrappedCommand)
            if (rejectDetached) return ShellOutcome.Rejected
            // 模拟服务器执行 wrapped 后回显 __ASSH_STARTED__:<id>
            val echoed = Regex("__ASSH_STARTED__:\\S+").find(wrappedCommand)?.value ?: ""
            return ShellOutcome.Done(ExecResult(echoed, "", 0, false, false, 5))
        }
        override suspend fun runReadonly(command: String, timeoutSec: Int): ShellOutcome {
            readonly = command to timeoutSec
            return readonlyOutcome ?: ShellOutcome.Done(ExecResult("", "", 0, false, false, 5))
        }
        override suspend fun fetchUrl(url: String) = ""
        override suspend fun search(query: String) = ""
    }

    @Test
    fun `start_job builds setsid wrapper and returns job_id`() = runBlocking {
        val ctx = FakeJobContext()
        val outcome = StartJobTool().execute("""{"command":"apt-get -y upgrade","why":"升级"}""", ctx)
        val d = ctx.detached!!
        assertEquals("apt-get -y upgrade", d.first)   // 内层命令原样传入（确认/展示用）
        assertEquals("升级", d.second)
        val w = d.third
        assertTrue("应优先 setsid", w.contains("command -v setsid"))
        assertTrue("应有 setsid", w.contains("setsid"))
        assertTrue("应回退 nohup", w.contains("nohup"))
        assertTrue("应建目录 700", w.contains("mkdir -p \$HOME/.assh/jobs"))
        assertTrue("应记进程组号", w.contains(".pgid"))
        assertTrue("应有完成哨兵", w.contains("__ASSH_EXIT__:\$?"))
        assertTrue("应内嵌原命令", w.contains("apt-get -y upgrade"))
        assertTrue((outcome as ToolOutcome.Continue).toolResult.contains("job_id="))
    }

    @Test
    fun `start_job single-quote-escapes the command`() = runBlocking {
        val ctx = FakeJobContext()
        StartJobTool().execute("""{"command":"echo 'hi there'"}""", ctx)
        // 内层脚本以单引号交给 sh -c，命令里的 ' 必须转义为 '\''
        assertTrue(ctx.detached!!.third.contains("'\\''"))
    }

    @Test
    fun `start_job blank command ignored without touching ctx`() = runBlocking {
        val ctx = FakeJobContext()
        val outcome = StartJobTool().execute("""{"command":""}""", ctx)
        assertNull(ctx.detached)
        assertTrue(outcome is ToolOutcome.Continue)
    }

    @Test
    fun `start_job relays rejection`() = runBlocking {
        val ctx = FakeJobContext(rejectDetached = true)
        val outcome = StartJobTool().execute("""{"command":"rm -rf /data"}""", ctx)
        assertTrue((outcome as ToolOutcome.Continue).toolResult.contains("拒绝"))
    }

    @Test
    fun `check_job builds probe with offset and parses running`() = runBlocking {
        val ctx = FakeJobContext(
            readonlyOutcome = ShellOutcome.Done(
                ExecResult("__ASSH_OFFSET__:42\n__ASSH_TAIL__\nhello world\n__ASSH_STATUS__:", "", 0, false, false, 5)
            )
        )
        val outcome = CheckJobTool().execute("""{"job_id":"j1_a","from_offset":10}""", ctx)
        val cmd = ctx.readonly!!.first
        assertTrue("from_offset 10 → tail -c +11", cmd.contains("tail -c +11"))
        assertTrue(cmd.contains("j1_a.log"))
        assertTrue(cmd.contains("__ASSH_EXIT__"))
        val t = (outcome as ToolOutcome.Continue).toolResult
        assertTrue(t.contains("仍在运行"))
        assertTrue(t.contains("next_offset=42"))
        assertTrue(t.contains("hello world"))
    }

    @Test
    fun `check_job parses finished exit code`() = runBlocking {
        val ctx = FakeJobContext(
            readonlyOutcome = ShellOutcome.Done(
                ExecResult("__ASSH_OFFSET__:100\n__ASSH_TAIL__\ndone\n__ASSH_STATUS__:__ASSH_EXIT__:0", "", 0, false, false, 5)
            )
        )
        val outcome = CheckJobTool().execute("""{"job_id":"j2"}""", ctx)
        val t = (outcome as ToolOutcome.Continue).toolResult
        assertTrue(t.contains("已结束"))
        assertTrue(t.contains("退出码=0"))
    }

    @Test
    fun `check_job reports missing log`() = runBlocking {
        val ctx = FakeJobContext(readonlyOutcome = ShellOutcome.Done(ExecResult("__ASSH_NOLOG__", "", 0, false, false, 5)))
        val outcome = CheckJobTool().execute("""{"job_id":"jx"}""", ctx)
        assertTrue((outcome as ToolOutcome.Continue).toolResult.contains("不存在"))
    }

    @Test
    fun `check_job tolerates mid-query interruption`() = runBlocking {
        val ctx = FakeJobContext(
            readonlyOutcome = ShellOutcome.Done(ExecResult("", "", null, false, false, 5, interrupted = true, reconnected = true))
        )
        val outcome = CheckJobTool().execute("""{"job_id":"jx"}""", ctx)
        assertTrue((outcome as ToolOutcome.Continue).toolResult.contains("重试"))
    }

    @Test
    fun `kill_job sends group-then-pid TERM`() = runBlocking {
        val ctx = FakeJobContext(readonlyOutcome = ShellOutcome.Done(ExecResult("__ASSH_KILLED__:1234", "", 0, false, false, 5)))
        val outcome = KillJobTool().execute("""{"job_id":"j9"}""", ctx)
        val cmd = ctx.readonly!!.first
        assertTrue("应按进程组杀", cmd.contains("kill -TERM -"))
        assertTrue(cmd.contains("j9.pgid"))
        assertTrue((outcome as ToolOutcome.Continue).toolResult.contains("终止信号"))
    }

    @Test
    fun `registry exposes job tools`() {
        val reg = ToolRegistry(listOf(StartJobTool(), CheckJobTool(), KillJobTool()))
        assertEquals(listOf("start_job", "check_job", "kill_job"), reg.specs.map { it.name })
        assertTrue(reg.get("start_job") is StartJobTool)
    }
}
