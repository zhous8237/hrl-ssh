package com.assh.ai.llm

import kotlinx.serialization.json.Json

/**
 * 按 [LlmProvider] 提供 [LlmClient] 实例。两个实现各持有独立 OkHttpClient，
 * 可安全复用。进程内单例（挂在 AsshApp）。
 */
class LlmClientFactory {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val openAi by lazy { OpenAiClient(json) }
    private val claude by lazy { ClaudeClient(json) }

    fun forProvider(provider: LlmProvider): LlmClient = when (provider) {
        LlmProvider.OPENAI -> openAi
        LlmProvider.CLAUDE -> claude
    }

    fun forConfig(config: LlmConfig): LlmClient = forProvider(config.provider)
}
