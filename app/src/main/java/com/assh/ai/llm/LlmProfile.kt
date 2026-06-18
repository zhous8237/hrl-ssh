package com.assh.ai.llm

import kotlinx.serialization.Serializable

/**
 * 一套可复用的 LLM 配置（持久化单元）。可保存多条，由用户选中其一作为当前使用。
 *
 * [encKeyB64] 是 Keystore 加密后的 Base64 密文（不是明文），随 JSON 一起存 DataStore。
 * 明文 key 只在 [com.assh.ai.AgentPreferences.resolveActiveConfig] 时瞬时解出。
 */
@Serializable
data class LlmProfile(
    val id: String,
    val name: String,
    val provider: LlmProvider,
    val baseUrl: String,
    val model: String,
    val encKeyB64: String? = null,
    val rpmLimit: Int = 5,            // 每分钟请求上限（平台限流配额），默认 5；0=不限制
    val updatedAt: Long = 0L          // LWW 合并用（WebDAV 同步）
) {
    val hasKey: Boolean get() = !encKeyB64.isNullOrBlank()
}
