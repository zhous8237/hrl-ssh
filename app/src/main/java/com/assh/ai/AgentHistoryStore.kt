package com.assh.ai

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.assh.data.crypto.CryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.agentHistoryStore by preferencesDataStore(name = "agent_history")

/**
 * AI 运维任务历史持久化（DataStore + JSON）。最近的排在前，最多保留 [MAX_RECORDS] 条。
 * 引擎每轮处理结束按 sessionId upsert 当前会话快照；用户可在历史页删除。
 */
class AgentHistoryStore(private val context: Context) {

    private val kHistory = stringPreferencesKey("history_json")
    private val json = Json { ignoreUnknownKeys = true }

    val history: Flow<List<AgentRunRecord>> =
        context.agentHistoryStore.data.map { parse(it[kHistory]) }

    suspend fun get(id: String): AgentRunRecord? =
        parse(context.agentHistoryStore.data.first()[kHistory]).firstOrNull { it.id == id }

    /** 按 id 更新或插入（新记录置顶），裁剪到上限 */
    suspend fun upsert(record: AgentRunRecord) {
        context.agentHistoryStore.edit { p ->
            val list = parse(p[kHistory]).toMutableList()
            val idx = list.indexOfFirst { it.id == record.id }
            if (idx >= 0) list[idx] = record else list.add(0, record)
            val trimmed = list.sortedByDescending { it.updatedAt }.take(MAX_RECORDS)
            p[kHistory] = serialize(trimmed)
        }
    }

    suspend fun delete(id: String) {
        context.agentHistoryStore.edit { p ->
            p[kHistory] = serialize(parse(p[kHistory]).filterNot { it.id == id })
        }
    }

    suspend fun clear() {
        context.agentHistoryStore.edit { it.remove(kHistory) }
    }

    private fun parse(s: String?): List<AgentRunRecord> {
        if (s.isNullOrBlank()) return emptyList()
        // 新数据是 Keystore 加密后的 Base64；旧数据为明文 JSON——解密失败则按明文解析，实现平滑迁移
        val jsonStr = runCatching { CryptoManager.decryptString(Base64.decode(s, Base64.NO_WRAP)) }.getOrElse { s }
        return runCatching { json.decodeFromString<List<AgentRunRecord>>(jsonStr) }.getOrDefault(emptyList())
    }

    /** 会话历史含命令输出（可能有主机名/IP/凭据片段），与 API Key、主机凭据一样走 Keystore 加密落盘 */
    private fun serialize(list: List<AgentRunRecord>): String =
        Base64.encodeToString(CryptoManager.encryptString(json.encodeToString(list)), Base64.NO_WRAP)

    companion object {
        private const val MAX_RECORDS = 50
    }
}
