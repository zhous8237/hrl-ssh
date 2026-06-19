package com.assh.ai.tools

import com.assh.ai.llm.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.random.Random

/**
 * 服务器端**持久化后台任务**工具（会话持久化）：长/改状态任务（apt/dnf 升级、编译、迁移、
 * 大传输）用 [StartJobTool] 以 setsid detached 跑、输出落日志、哨兵记退出码——断线/重连甚至
 * resume 后仍可用 [CheckJobTool] 续看、用 [KillJobTool] 终止。
 *
 * 关键约定（与 [CheckJobTool]/[KillJobTool] 共享）：
 * - 工作目录 `$HOME/.assh/jobs/`（700）；每个任务两文件：`<id>.log`（输出）、`<id>.pgid`（进程组号）。
 * - 完成哨兵：日志末尾追加 `__ASSH_EXIT__:<退出码>`。
 */

private val jobJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun obj(argsJson: String) =
    runCatching { jobJson.parseToJsonElement(argsJson).jsonObject }.getOrNull()

private fun strArg(argsJson: String, key: String): String =
    obj(argsJson)?.get(key)?.jsonPrimitive?.contentOrNull.orEmpty()

private fun intArg(argsJson: String, key: String, default: Int): Int =
    obj(argsJson)?.get(key)?.jsonPrimitive?.intOrNull ?: default

/** 唯一 job id（文件名安全：字母/数字/下划线/十六进制） */
private fun newJobId(): String = "j" + System.currentTimeMillis() + "_" + Integer.toHexString(Random.nextInt(0x10000))

/** 用单引号安全包裹给 `sh -c` 用：把内部的 ' 转义为 '\'' （R3 转义面） */
internal fun shSingleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

private const val JOBS_DIR = "\$HOME/.assh/jobs"

/** 在服务器端 detached 启动一个长任务 */
class StartJobTool : Tool {
    override val spec = ToolSpec(
        name = "start_job",
        description = "在服务器端以后台 detached 方式启动一个长任务（预计耗时较长、或中断会留下半完成状态的命令，" +
            "如包管理升级、编译、数据迁移、大文件传输）。任务在 SSH 断线/重连后仍存活，返回 job_id，" +
            "之后用 check_job 查询进度与退出码、用 kill_job 终止。注意：命令本身不要自带重定向(>)或后台符(&)。",
        parametersJsonSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("command") { put("type", "string"); put("description", "要后台执行的完整 shell 命令（勿自带 > 重定向或 & 后台符）") }
                putJsonObject("why") { put("type", "string"); put("description", "用一句中文说明这个任务的目的") }
            }
            putJsonArray("required") { add("command") }
        }
    )

    override suspend fun execute(argumentsJson: String, ctx: ToolContext): ToolOutcome {
        val o = obj(argumentsJson)
        val cmd = o?.get("command")?.jsonPrimitive?.contentOrNull.orEmpty()
        val why = o?.get("why")?.jsonPrimitive?.contentOrNull
        if (cmd.isBlank()) {
            return ToolOutcome.Continue("command 为空或参数解析失败（也可能是上一条回复因长度上限被截断），已忽略。请重发完整命令。")
        }
        val id = newJobId()
        val log = "$JOBS_DIR/$id.log"
        val pgid = "$JOBS_DIR/$id.pgid"
        // 内层脚本（交给 sh -c 单引号执行）：记进程组号 → 执行命令(输出与退出码落日志)。$$ 在 setsid 下即 pgid。
        val inner = "echo \$\$ > $pgid; { $cmd ; } > $log 2>&1; echo \"__ASSH_EXIT__:\$?\" >> $log"
        // 优先 setsid（脱离会话、免 SIGHUP）；无则回退 nohup。整体后台化、立即返回。
        val wrapped = "mkdir -p $JOBS_DIR && chmod 700 $JOBS_DIR; " +
            "L=nohup; command -v setsid >/dev/null 2>&1 && L=setsid; " +
            "\$L sh -c ${shSingleQuote(inner)} </dev/null >/dev/null 2>&1 & " +
            "echo \"__ASSH_STARTED__:$id\""
        return when (val r = ctx.runDetachedJob(cmd, why, wrapped)) {
            is ShellOutcome.Done -> {
                val started = r.result.stdout.contains("__ASSH_STARTED__:$id")
                if (started) ToolOutcome.Continue(
                    "已在后台启动任务 job_id=$id（日志 $log）。它在服务器端 detached 运行，SSH 断线/重连后仍存活。" +
                        "用 check_job(job_id=\"$id\") 查询进度与退出码；如需终止用 kill_job(job_id=\"$id\")。"
                ) else ToolOutcome.Continue(
                    "尝试启动后台任务 job_id=$id，但未确认启动成功。stderr=${r.result.stderr.take(500)}。" +
                        "可用 check_job(job_id=\"$id\") 核实，或改用 run_command。"
                )
            }
            ShellOutcome.Rejected -> ToolOutcome.Continue("用户拒绝执行该命令。请改用更安全的方案，或调用 finish 说明无法继续。")
            ShellOutcome.ConnectionClosed -> throw IllegalStateException("连接已关闭")
        }
    }
}

/** 查询后台任务的进度与退出码（增量读取日志） */
class CheckJobTool : Tool {
    override val spec = ToolSpec(
        name = "check_job",
        description = "查询 start_job 启动的后台任务：增量返回新输出、是否已结束及退出码。服务器端最多阻塞约 30s 等进度。" +
            "用返回的 next_offset 作为下次 from_offset 实现增量读取。SSH 断线/重连甚至 resume 后仍可用。",
        parametersJsonSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("job_id") { put("type", "string"); put("description", "start_job 返回的 job_id") }
                putJsonObject("from_offset") { put("type", "integer"); put("description", "上次返回的 next_offset（首次填 0）；用于只取新增输出") }
            }
            putJsonArray("required") { add("job_id") }
        }
    )

    override suspend fun execute(argumentsJson: String, ctx: ToolContext): ToolOutcome {
        val id = strArg(argumentsJson, "job_id")
        if (id.isBlank()) return ToolOutcome.Continue("job_id 为空，已忽略。请提供 start_job 返回的 job_id。")
        val fromOffset = intArg(argumentsJson, "from_offset", 0).coerceAtLeast(0)
        val log = "$JOBS_DIR/$id.log"
        val startByte = fromOffset + 1   // tail -c +N 是 1 基字节偏移
        // 只读探测：无日志→NOLOG；最多等 ~30s 直到出现完成哨兵；输出总大小(next_offset)、增量内容、完成状态。
        val probe = "LOG=$log; " +
            "if [ ! -f \"\$LOG\" ]; then echo __ASSH_NOLOG__; else " +
            "i=0; while [ \$i -lt 6 ]; do grep -aq __ASSH_EXIT__ \"\$LOG\" && break; sleep 5; i=\$((i+1)); done; " +
            "echo \"__ASSH_OFFSET__:\$(wc -c < \"\$LOG\" | tr -d ' ')\"; " +
            "echo __ASSH_TAIL__; tail -c +$startByte \"\$LOG\"; echo; " +
            "echo \"__ASSH_STATUS__:\$(grep -a __ASSH_EXIT__ \"\$LOG\" | tail -n1)\"; fi"
        return when (val r = ctx.runReadonly(probe, timeoutSec = 45)) {
            is ShellOutcome.Done -> {
                val res = r.result
                if (res.interrupted) return ToolOutcome.Continue("查询任务 $id 时连接闪断（已自动重连），未取到完整状态，请再次 check_job 重试。")
                val out = res.stdout
                if (out.contains("__ASSH_NOLOG__")) {
                    return ToolOutcome.Continue("任务 $id 不存在：日志文件不在（可能 id 有误，或任务完成已超 24h 被自动清理）。")
                }
                val offset = Regex("__ASSH_OFFSET__:(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: fromOffset
                val tail = out.substringAfter("__ASSH_TAIL__\n", "").substringBefore("\n__ASSH_STATUS__")
                val exit = Regex("__ASSH_EXIT__:(-?\\d+)").find(out.substringAfter("__ASSH_STATUS__"))?.groupValues?.get(1)
                val status = if (exit != null) "已结束，退出码=$exit" else "仍在运行"
                ToolOutcome.Continue(
                    "任务 $id：$status。next_offset=$offset\n--- 新增输出 ---\n${tail.trimEnd().ifBlank { "(无新增输出)" }}"
                )
            }
            ShellOutcome.Rejected -> ToolOutcome.Continue("查询被拒绝。")
            ShellOutcome.ConnectionClosed -> throw IllegalStateException("连接已关闭")
        }
    }
}

/** 终止一个后台任务（按进程组发送 TERM） */
class KillJobTool : Tool {
    override val spec = ToolSpec(
        name = "kill_job",
        description = "终止 start_job 启动的后台任务（向其进程组发送 SIGTERM）。用于停止跑错或不再需要的长任务。",
        parametersJsonSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("job_id") { put("type", "string"); put("description", "start_job 返回的 job_id") }
            }
            putJsonArray("required") { add("job_id") }
        }
    )

    override suspend fun execute(argumentsJson: String, ctx: ToolContext): ToolOutcome {
        val id = strArg(argumentsJson, "job_id")
        if (id.isBlank()) return ToolOutcome.Continue("job_id 为空，已忽略。")
        val pgid = "$JOBS_DIR/$id.pgid"
        // 先按进程组杀（setsid 下 pgid 文件存的是组长 pid，-P 即整组）；失败再按单 pid 杀（nohup 回退场景）。
        val kill = "P=\$(cat \"$pgid\" 2>/dev/null); " +
            "if [ -n \"\$P\" ]; then " +
            "kill -TERM -\"\$P\" 2>/dev/null || kill -TERM \"\$P\" 2>/dev/null; echo \"__ASSH_KILLED__:\$P\"; " +
            "else echo __ASSH_NOPID__; fi"
        return when (val r = ctx.runReadonly(kill, timeoutSec = 10)) {
            is ShellOutcome.Done -> {
                if (r.result.stdout.contains("__ASSH_NOPID__")) ToolOutcome.Continue("未找到任务 $id 的进程组号（可能未启动、已结束或 id 有误）。")
                else ToolOutcome.Continue("已向任务 $id 的进程组发送终止信号。可用 check_job 确认其退出码。")
            }
            ShellOutcome.Rejected -> ToolOutcome.Continue("终止被拒绝。")
            ShellOutcome.ConnectionClosed -> throw IllegalStateException("连接已关闭")
        }
    }
}
