package com.assh.ai.tools

import com.assh.ai.ssh.ExecResult
import com.assh.ai.llm.ToolSpec

/**
 * 工具执行结果（C1 深化）：
 * - [Continue] 把字符串结果回灌给 LLM，循环继续。
 * - [Finish] 结束本轮，向用户汇报。
 */
sealed interface ToolOutcome {
    data class Continue(val toolResult: String) : ToolOutcome
    data class Finish(val success: Boolean, val summary: String) : ToolOutcome
}

/**
 * 工具运行时能对“世界”做的事（由 [com.assh.ai.SshAgentEngine] 实现）。
 * 把需要触碰引擎状态（确认弹窗 / 时间线 / 部分输出 / SSH 执行）的能力收在这里，
 * 让 [Tool] 实现保持薄而纯净、可用 fake context 单测。
 */
interface ToolContext {
    /** 向时间线追加一条通知 */
    fun notice(text: String)

    /**
     * 在目标服务器执行命令：内部完成危险检测 + 按策略确认 + 时间线展示 + 部分输出流。
     * 返回 null 表示连接已关闭。被用户拒绝执行时返回 [CommandRejected]。
     */
    suspend fun runShell(command: String, why: String?, timeoutSec: Int): ShellOutcome

    /**
     * 以服务器端 detached（setsid）方式启动长任务：危险检测+确认认的是 [innerCommand]
     * （用户能懂的原始命令），实际执行 [wrappedCommand]（setsid 包装串，瞬时返回）。
     * 时间线展示 [innerCommand]。供 start_job 使用。
     */
    suspend fun runDetachedJob(innerCommand: String, why: String?, wrappedCommand: String): ShellOutcome

    /**
     * 执行只读/内部命令（tail 日志、读哨兵、kill 等），**不弹确认、不进时间线**——
     * 供 check_job / kill_job 轮询，避免 ALWAYS 确认策略下刷屏。
     */
    suspend fun runReadonly(command: String, timeoutSec: Int): ShellOutcome

    /** 抓取 URL 文本（已做 SSRF 防护，结果由调用方按不可信数据包裹） */
    suspend fun fetchUrl(url: String): String

    /** 联网搜索 */
    suspend fun search(query: String): String
}

/** [ToolContext.runShell] 的结果：执行完成 / 被用户拒绝 / 连接关闭 */
sealed interface ShellOutcome {
    data class Done(val result: ExecResult) : ShellOutcome
    data object Rejected : ShellOutcome
    data object ConnectionClosed : ShellOutcome
}

/**
 * 一个工具 = 规格（给 LLM）+ 执行（给循环），绑在一起。
 * 此前规格列表（AgentTools.all）与派发 when(call.name) 是两份手工同步的平行列表；
 * 现在加工具 = 在 [ToolRegistry] 注册一个 [Tool]，规格与行为不再可能漂移。
 */
interface Tool {
    val spec: ToolSpec
    val name: String get() = spec.name

    /** 执行一次调用。[argumentsJson] 为 LLM 传入的参数 JSON 文本。 */
    suspend fun execute(argumentsJson: String, ctx: ToolContext): ToolOutcome
}

/**
 * 工具注册表：规格列表与派发表同源。未知工具 [get] 返回 null，由调用方显式处理，
 * 不再静默落到 when 的 else 分支。
 */
class ToolRegistry(private val tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }
    val specs: List<ToolSpec> = tools.map { it.spec }
    fun get(name: String): Tool? = byName[name]
}
