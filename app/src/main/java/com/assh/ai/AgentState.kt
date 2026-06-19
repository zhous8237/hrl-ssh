package com.assh.ai

import com.assh.ai.ssh.ExecResult

/** Agent 运行阶段 */
enum class AgentPhase { IDLE, CONNECTING, THINKING, EXECUTING, WAITING_CONFIRM, WAITING_HOSTKEY, AWAITING_FOLLOWUP, DONE, ERROR, CANCELLED }

/** 命令执行状态 */
enum class CmdStatus { RUNNING, DONE }

/** 时间线条目（UI 按顺序渲染） */
sealed interface TimelineItem {
    /** 用户输入的指令（首个目标或追加） */
    data class UserText(val text: String) : TimelineItem

    /** 模型的思考 / 说明文字 */
    data class AiText(val text: String) : TimelineItem

    /** 一条命令及其执行结果（RUNNING 时 result 为 null，partialOutput 为实时累积的 stdout 预览） */
    data class Command(
        val command: String,
        val why: String?,
        val status: CmdStatus,
        val result: ExecResult? = null,
        val partialOutput: String? = null
    ) : TimelineItem

    /** 提示性条目（如用户拒绝执行） */
    data class Notice(val text: String) : TimelineItem
}

/** 待确认的危险命令 */
data class PendingConfirm(
    val command: String,
    val why: String?,
    val classification: Classification
)

/** 待确认的 host key 变更（TOFU 比对失败，可能是服务器重装也可能是 MITM） */
data class PendingHostKey(
    val hostPort: String,
    val savedFingerprint: String,
    val actualFingerprint: String
)

/** Agent 对外状态快照，UI collect 它来渲染 */
data class AgentState(
    val phase: AgentPhase = AgentPhase.IDLE,
    val timeline: List<TimelineItem> = emptyList(),
    val step: Int = 0,
    val pendingConfirm: PendingConfirm? = null,
    val pendingHostKey: PendingHostKey? = null,
    val finishedMessage: String? = null,   // 非空触发一次 Toast，消费后清空
    val success: Boolean = false,
    /** 连接已断、正在后台重连：phase 仍为 AWAITING_FOLLOWUP（可点「继续」立即重连） */
    val disconnected: Boolean = false
) {
    val running: Boolean
        get() = phase == AgentPhase.CONNECTING || phase == AgentPhase.THINKING ||
            phase == AgentPhase.EXECUTING || phase == AgentPhase.WAITING_CONFIRM ||
            phase == AgentPhase.WAITING_HOSTKEY

    /** 会话保持中、AI 空闲，可追加指令继续或结束会话 */
    val canContinue: Boolean get() = phase == AgentPhase.AWAITING_FOLLOWUP

    /** 会话存活（SSH 连着）：运行中或等待追加 */
    val sessionActive: Boolean get() = running || canContinue
}
