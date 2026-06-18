package com.assh.ai.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Anthropic Claude 客户端（/v1/messages，tool use）。
 *
 * 与 OpenAI 的关键差异：system 走顶层字段；工具结果是 user 消息里的
 * tool_result content block（连续的 [Role.TOOL] 消息须合并进同一条 user 消息，
 * 否则 id 配对错乱 / 报 messages 顺序错误）；max_tokens 必填。
 */
class ClaudeClient(
    private val json: Json,
    private val maxTokens: Int = 8192,
    private val maxRetries: Int = 3,
    private val retryDelayMs: Long = 30_000
) : LlmClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mediaJson = "application/json".toMediaType()

    /** 请求节流：相邻两次 chat 间隔按当前配置的 RPM 推导，主动避免触发限流 */
    private val throttle = RequestThrottle()

    override suspend fun chat(
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        config: LlmConfig
    ): LlmResponse = withContext(Dispatchers.IO) {
        val b = config.baseUrl.trim().trimEnd('/')
        val url = if (b.endsWith("/v1")) "$b/messages" else "$b/v1/messages"
        val payload = buildJsonObject {
            put("model", config.model)
            put("max_tokens", maxTokens)
            if (system.isNotBlank()) put("system", system)
            put("messages", buildMessages(messages))
            if (tools.isNotEmpty()) put("tools", buildTools(tools))
        }
        val body = payload.toString().toRequestBody(mediaJson)
        throttle.await(config.minIntervalMs)
        // 429 / 5xx 限流或瞬时错误自动退避重试；用尽后转普通 LlmException 抛出
        retryOnTransient(maxRetries, retryDelayMs) {
            val req = Request.Builder()
                .url(url)
                .header("x-api-key", config.apiKey)
                .header("anthropic-version", "2023-06-01")
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
        val b = config.baseUrl.trim().trimEnd('/')
        val url = if (b.endsWith("/v1")) "$b/models" else "$b/v1/models"
        val req = Request.Builder()
            .url(url)
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
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
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@withContext emptyList()
        val data = root["data"] as? JsonArray ?: return@withContext emptyList()
        data.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.distinct().sorted()
    }

    private fun buildMessages(messages: List<ChatMessage>): JsonArray = buildJsonArray {
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            when (m.role) {
                Role.USER, Role.SYSTEM -> {
                    addJsonObject { put("role", "user"); put("content", m.text.orEmpty()) }
                    i++
                }
                Role.ASSISTANT -> {
                    addJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") {
                            if (!m.text.isNullOrBlank()) addJsonObject {
                                put("type", "text"); put("text", m.text)
                            }
                            for (tc in m.toolCalls) addJsonObject {
                                put("type", "tool_use")
                                put("id", tc.id)
                                put("name", tc.name)
                                put("input", parseObject(tc.argumentsJson))
                            }
                        }
                    }
                    i++
                }
                Role.TOOL -> {
                    // 合并连续 TOOL → 一条 user 消息的 tool_result block 数组
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            while (i < messages.size && messages[i].role == Role.TOOL) {
                                val t = messages[i]
                                addJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", t.toolCallId.orEmpty())
                                    put("content", t.toolResult.orEmpty())
                                }
                                i++
                            }
                        }
                    }
                }
            }
        }
    }

    private fun buildTools(tools: List<ToolSpec>): JsonArray = buildJsonArray {
        for (t in tools) addJsonObject {
            put("name", t.name)
            put("description", t.description)
            put("input_schema", t.parametersJsonSchema)
        }
    }

    private fun parseObject(s: String): JsonObject =
        runCatching { json.parseToJsonElement(s).jsonObject }.getOrElse { buildJsonObject { } }

    private fun parseResponse(body: String): LlmResponse {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw LlmException("响应不是合法 JSON：${body.take(200)}") }
        val content = root["content"] as? JsonArray
            ?: throw LlmException("响应缺少 content：${body.take(200)}")
        val textBuf = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        for (block in content) {
            val o = block.jsonObject
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> textBuf.append(o["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                "tool_use" -> toolCalls.add(
                    ToolCall(
                        id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
                        name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        argumentsJson = (o["input"] ?: buildJsonObject { }).toString()
                    )
                )
            }
        }
        val text = textBuf.toString().takeIf { it.isNotBlank() }
        val stop = when (root["stop_reason"]?.jsonPrimitive?.contentOrNull) {
            "tool_use" -> StopReason.TOOL_USE
            "end_turn", "stop_sequence" -> StopReason.END
            "max_tokens" -> StopReason.LENGTH
            else -> if (toolCalls.isNotEmpty()) StopReason.TOOL_USE else StopReason.END
        }
        return LlmResponse(text, toolCalls, stop, ChatMessage.assistant(text, toolCalls))
    }

    /** 按状态码选异常类型：429 / 5xx 视为限流/瞬时错误可重试，其余为终态 */
    private fun httpError(code: Int, body: String): LlmException {
        val msg = mapHttpError(code, body)
        return if (code == 429 || code in 500..599) RetryableLlmException(msg) else LlmException(msg)
    }

    private fun mapHttpError(code: Int, body: String): String {
        val detail = body.take(300)
        return when (code) {
            401 -> "鉴权失败（401）：API Key 无效。$detail"
            403 -> "无权限（403）。$detail"
            404 -> "接口不存在（404）：请检查 base_url。$detail"
            429 -> "请求过于频繁或额度不足（429）。$detail"
            in 500..599 -> "服务端错误（$code）。$detail"
            else -> "请求失败（HTTP $code）：$detail"
        }
    }
}
