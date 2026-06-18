package com.assh.ai

import com.assh.ai.llm.ChatMessage
import com.assh.ai.llm.Role
import com.assh.ai.llm.ToolCall
import com.assh.ai.ssh.ExecResult
import kotlinx.serialization.Serializable

/**
 * 一次 Agent 会话的持久化记录。除展示用的 [entries]（[TimelineItem] 镜像），
 * 还保存 [hostId] 与可序列化的 [messages]（LLM 完整对话），以便下次从历史**恢复并继续**会话。
 */
@Serializable
data class AgentRunRecord(
    val id: String,
    val hostLabel: String,
    val title: String,
    val startedAt: Long,
    val updatedAt: Long,
    val success: Boolean,
    val phaseLabel: String,
    val entries: List<RecordEntry>,
    val hostId: Long = -1L,
    val messages: List<StoredMessage> = emptyList()
)

@Serializable
data class RecordEntry(
    val kind: String,          // "user" | "ai" | "command" | "notice"
    val text: String = "",
    val command: String? = null,
    val why: String? = null,
    val exit: Int? = null,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    val stdout: String = "",
    val stderr: String = ""
)

/** ChatMessage 的可序列化镜像（恢复会话时回填 LLM 对话历史） */
@Serializable
data class StoredMessage(
    val role: String,
    val text: String? = null,
    val toolCalls: List<StoredToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolResult: String? = null
)

@Serializable
data class StoredToolCall(val id: String, val name: String, val argumentsJson: String)

// —— 转换 ——

fun TimelineItem.toRecordEntry(): RecordEntry = when (this) {
    is TimelineItem.UserText -> RecordEntry(kind = "user", text = text)
    is TimelineItem.AiText -> RecordEntry(kind = "ai", text = text)
    is TimelineItem.Notice -> RecordEntry(kind = "notice", text = text)
    is TimelineItem.Command -> RecordEntry(
        kind = "command",
        command = command,
        why = why,
        exit = result?.exitStatus,
        timedOut = result?.timedOut ?: false,
        truncated = result?.truncated ?: false,
        stdout = result?.stdout.orEmpty(),
        stderr = result?.stderr.orEmpty()
    )
}

fun RecordEntry.toTimelineItem(): TimelineItem = when (kind) {
    "user" -> TimelineItem.UserText(text)
    "notice" -> TimelineItem.Notice(text)
    "command" -> TimelineItem.Command(
        command = command.orEmpty(),
        why = why,
        status = CmdStatus.DONE,
        result = ExecResult(stdout, stderr, exit, truncated, timedOut, 0)
    )
    else -> TimelineItem.AiText(text)
}

fun ChatMessage.toStored(): StoredMessage = StoredMessage(
    role = role.name,
    text = text,
    toolCalls = toolCalls.map { StoredToolCall(it.id, it.name, it.argumentsJson) },
    toolCallId = toolCallId,
    toolResult = toolResult
)

fun StoredMessage.toChatMessage(): ChatMessage = ChatMessage(
    role = runCatching { Role.valueOf(role) }.getOrDefault(Role.USER),
    text = text,
    toolCalls = toolCalls.map { ToolCall(it.id, it.name, it.argumentsJson) },
    toolCallId = toolCallId,
    toolResult = toolResult
)
