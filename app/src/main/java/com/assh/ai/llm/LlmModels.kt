package com.assh.ai.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 支持的大模型供应商 */
@Serializable
enum class LlmProvider { OPENAI, CLAUDE }

/**
 * 解析后的 LLM 连接配置（明文 api_key，仅瞬时存在）。
 * 持久化（含 key 加密）见 [com.assh.ai.AgentPreferences]。
 */
data class LlmConfig(
    val provider: LlmProvider,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** 每分钟请求数上限（平台限流配额）。默认 5（适配多数受限平台）；填 0 表示不限制 */
    val rpmLimit: Int = 5
) {
    val isUsable: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /**
     * 相邻两次请求的最小间隔（毫秒），由 [rpmLimit] 推导，额外 +800ms 余量防边界误判。
     * rpmLimit<=0（用户显式填 0）表示不限制，间隔为 0。
     * 例：默认 rpmLimit=5 → 60000/5+800 = 12800ms，恰好压在"5 次/分钟"配额内。
     */
    val minIntervalMs: Long
        get() = if (rpmLimit > 0) 60_000L / rpmLimit + 800L else 0L

    // 不泄露 key（仿 ResolvedHostConfig.toString）
    override fun toString(): String = "LlmConfig(provider=$provider, model=$model, rpm=$rpmLimit)"
}

/** 对话角色 */
enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

/**
 * provider 无关的对话消息。不同字段按 role 取用：
 * - USER / SYSTEM：仅 [text]
 * - ASSISTANT：[text]（可空）+ [toolCalls]
 * - TOOL：[toolCallId] + [toolResult]（工具执行结果回灌）
 */
data class ChatMessage(
    val role: Role,
    val text: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolResult: String? = null
) {
    companion object {
        fun user(text: String) = ChatMessage(Role.USER, text = text)
        fun assistant(text: String?, toolCalls: List<ToolCall> = emptyList()) =
            ChatMessage(Role.ASSISTANT, text = text, toolCalls = toolCalls)
        fun tool(toolCallId: String, result: String) =
            ChatMessage(Role.TOOL, toolCallId = toolCallId, toolResult = result)
    }
}

/** 工具声明（function calling / tool use）。[parametersJsonSchema] 是 JSON Schema 对象 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJsonSchema: JsonObject
)

/** 模型发起的一次工具调用。[argumentsJson] 为原始 JSON 字符串，由引擎解析 */
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

/** 模型本轮停止原因（折叠各 provider 差异） */
enum class StopReason { TOOL_USE, END, LENGTH, OTHER }

/**
 * 统一的模型响应。[rawAssistantMessage] 用于原样回写进对话历史，
 * 保证下一轮请求里 assistant 的 tool_calls 与后续 tool 结果配对。
 */
data class LlmResponse(
    val assistantText: String?,
    val toolCalls: List<ToolCall>,
    val stopReason: StopReason,
    val rawAssistantMessage: ChatMessage
)

/** LLM 调用失败（鉴权 / 余额 / 网络 / 格式等），message 含可读原因。可被子类化（见 RetryableLlmException） */
open class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)
