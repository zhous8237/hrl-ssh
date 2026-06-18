package com.assh.ai.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容客户端（/chat/completions，function calling）。
 * 适配官方 OpenAI、各类中转网关、本地推理服务——只要协议兼容即可。
 *
 * baseUrl 应填到版本段（如 https://api.openai.com/v1），实际请求 {baseUrl}/chat/completions。
 */
class OpenAiClient(
    private val json: Json,
    private val maxRetries: Int = 3,
    private val retryDelayMs: Long = 30_000
) : LlmClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // 模型思考可能较久
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mediaJson = "application/json".toMediaType()

    /** 请求节流：相邻两次 chat 间隔按当前配置的 RPM 推导，主动避免触发 provider 限流 */
    private val throttle = RequestThrottle()

    override suspend fun chat(
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        config: LlmConfig
    ): LlmResponse = withContext(Dispatchers.IO) {
        val url = config.baseUrl.trim().trimEnd('/') + "/chat/completions"
        val payload = buildJsonObject {
            put("model", config.model)
            put("messages", buildMessages(system, messages))
            if (tools.isNotEmpty()) {
                put("tools", buildTools(tools))
                put("tool_choice", "auto")
            }
        }
        val body = payload.toString().toRequestBody(mediaJson)
        throttle.await(config.minIntervalMs)
        // 限流/瞬时错误（429、5xx、choices 为空）自动退避重试；用尽后转普通 LlmException 抛出
        retryOnTransient(maxRetries, retryDelayMs) {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${config.apiKey}")
                .post(body)
                .build()
            val bodyStr = runCatching {
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw httpError(resp.code, text)
                    text
                }
            }.getOrElse { e ->
                if (e is LlmException) throw e
                throw LlmException("网络错误：${e.message ?: e.javaClass.simpleName}", e)
            }
            parseResponse(bodyStr)
        }
    }

    override suspend fun listModels(config: LlmConfig): List<String> = withContext(Dispatchers.IO) {
        val url = config.baseUrl.trim().trimEnd('/') + "/models"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .get()
            .build()
        val body = runCatching {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw LlmException(mapHttpError(resp.code, text))
                text
            }
        }.getOrElse { e ->
            if (e is LlmException) throw e
            throw LlmException("网络错误：${e.message ?: e.javaClass.simpleName}", e)
        }
        parseModels(body)
    }

    private fun parseModels(body: String): List<String> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val data = root["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.distinct().sorted()
    }

    private fun buildMessages(system: String, messages: List<ChatMessage>): JsonArray = buildJsonArray {
        if (system.isNotBlank()) addJsonObject {
            put("role", "system"); put("content", system)
        }
        for (m in messages) when (m.role) {
            Role.SYSTEM -> addJsonObject { put("role", "system"); put("content", m.text.orEmpty()) }
            Role.USER -> addJsonObject { put("role", "user"); put("content", m.text.orEmpty()) }
            Role.ASSISTANT -> addJsonObject {
                put("role", "assistant")
                put("content", m.text.orEmpty())
                if (m.toolCalls.isNotEmpty()) put("tool_calls", buildJsonArray {
                    for (tc in m.toolCalls) addJsonObject {
                        put("id", tc.id)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tc.name)
                            put("arguments", tc.argumentsJson)   // OpenAI: arguments 是字符串
                        }
                    }
                })
            }
            Role.TOOL -> addJsonObject {
                put("role", "tool")
                put("tool_call_id", m.toolCallId.orEmpty())
                put("content", m.toolResult.orEmpty())
            }
        }
    }

    private fun buildTools(tools: List<ToolSpec>): JsonArray = buildJsonArray {
        for (t in tools) addJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", t.name)
                put("description", t.description)
                put("parameters", t.parametersJsonSchema)
            }
        }
    }

    private fun parseResponse(body: String): LlmResponse {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw LlmException("响应不是合法 JSON：${body.take(200)}") }
        // 魔搭等会返回 choices=null / 空数组 + usage 全 0 的 200 响应——本质是被限流或
        // 模型暂不可用的伪装，按可重试处理（退避重试，而非直接报错结束会话）
        val choices = root["choices"] as? JsonArray
        if (choices.isNullOrEmpty()) {
            throw RetryableLlmException(
                "模型返回空响应（choices 为空），通常是被限流或该模型暂时不可用，正在重试…：${body.take(200)}"
            )
        }
        val choice = choices.firstOrNull()?.jsonObject
            ?: throw LlmException("响应 choices 格式异常：${body.take(200)}")
        val msg = choice["message"]?.jsonObject
            ?: throw LlmException("响应缺少 message")
        val content = msg["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val toolCalls = (msg["tool_calls"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { e ->
            val o = e.jsonObject
            // 非标准网关 / 流式拼接残缺时 function 或 name 可能缺失：安全解析、跳过非法项，避免 NPE 让整轮崩
            val fn = o["function"]?.jsonObject ?: return@mapNotNull null
            val name = fn["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ToolCall(
                id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
                name = name,
                argumentsJson = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
            )
        }
        val stop = when (choice["finish_reason"]?.jsonPrimitive?.contentOrNull) {
            "tool_calls" -> StopReason.TOOL_USE
            "stop" -> StopReason.END
            "length" -> StopReason.LENGTH
            else -> if (toolCalls.isNotEmpty()) StopReason.TOOL_USE else StopReason.END
        }
        return LlmResponse(content, toolCalls, stop, ChatMessage.assistant(content, toolCalls))
    }

    /** 按状态码选异常类型：429 / 5xx 视为限流/瞬时错误可重试，其余（401/403/404…）为终态 */
    private fun httpError(code: Int, body: String): LlmException {
        val msg = mapHttpError(code, body)
        return if (code == 429 || code in 500..599) RetryableLlmException(msg) else LlmException(msg)
    }

    private fun mapHttpError(code: Int, body: String): String {
        val detail = body.take(300)
        return when (code) {
            401 -> "鉴权失败（401）：API Key 无效。$detail"
            403 -> "无权限（403）。$detail"
            404 -> "接口不存在（404）：请检查 base_url 是否填到 /v1。$detail"
            429 -> "请求过于频繁或余额不足（429）。$detail"
            in 500..599 -> "服务端错误（$code）。$detail"
            else -> "请求失败（HTTP $code）：$detail"
        }
    }
}
