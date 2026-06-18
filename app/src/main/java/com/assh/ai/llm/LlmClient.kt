package com.assh.ai.llm

/**
 * provider 无关的大模型客户端。一次 [chat] 调用对应模型的一轮响应
 * （可能是纯文本，也可能携带若干工具调用）。
 *
 * 实现：[OpenAiClient]（/chat/completions）、[ClaudeClient]（/v1/messages）。
 * 由 [LlmClientFactory] 按 [LlmConfig.provider] 选择。
 */
interface LlmClient {
    /**
     * @param system 系统提示（由实现决定落点：OpenAI 放 messages 首条，Claude 放顶层 system）
     * @param messages 对话历史（不含 system）
     * @param tools 可用工具声明
     * @param config 端点 / 鉴权 / 模型
     * @throws LlmException 鉴权 / 网络 / 格式错误
     */
    suspend fun chat(
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        config: LlmConfig
    ): LlmResponse

    /**
     * 拉取该端点可用的模型 id 列表（OpenAI: GET /models；Anthropic: GET /v1/models）。
     * 部分中转网关可能不提供该接口或返回空——调用方应允许用户手动填写模型名。
     * @throws LlmException 鉴权 / 网络 / 接口不存在
     */
    suspend fun listModels(config: LlmConfig): List<String>
}
