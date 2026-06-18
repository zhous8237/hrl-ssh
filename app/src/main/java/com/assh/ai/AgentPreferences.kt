package com.assh.ai

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.assh.ai.llm.LlmConfig
import com.assh.ai.llm.LlmProfile
import com.assh.data.crypto.CryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.agentDataStore by preferencesDataStore(name = "agent_settings")

/**
 * AI 命令执行确认策略：
 * - [ALWAYS]：每条命令执行前都需用户确认（最谨慎，适合生产服务器）。
 * - [DANGEROUS_ONLY]：仅 [DangerousCommandDetector] 判为危险的命令需确认（默认，兼容旧行为）。
 * - [NEVER]：全部自动执行、不打断（最省心，风险自负）。
 */
enum class ConfirmPolicy { ALWAYS, DANGEROUS_ONLY, NEVER }

/**
 * AI 运维 Agent 的多套 LLM 配置持久化。
 *
 * 保存多条 [LlmProfile]（JSON 列表），由 [activeProfileId] 指定当前使用哪条。
 * 每条的 api_key 用 [CryptoManager] Keystore 加密后以 Base64 存进 profile 的 encKeyB64
 * 字段（密文随 JSON 一起落盘；明文只在 [resolveActiveConfig] 时瞬时解出、用完即弃）。
 */
class AgentPreferences(private val context: Context) {

    private val kProfiles = stringPreferencesKey("profiles_json")
    private val kActiveId = stringPreferencesKey("active_profile_id")
    private val kConfirmPolicy = stringPreferencesKey("confirm_policy")

    private val json = Json { ignoreUnknownKeys = true }

    val profiles: Flow<List<LlmProfile>> =
        context.agentDataStore.data.map { parseProfiles(it[kProfiles]) }

    val activeProfileId: Flow<String?> =
        context.agentDataStore.data.map { it[kActiveId] }

    /** 命令执行确认策略（默认 DANGEROUS_ONLY：仅危险命令需确认） */
    val confirmPolicy: Flow<ConfirmPolicy> =
        context.agentDataStore.data.map { parsePolicy(it[kConfirmPolicy]) }

    suspend fun listProfiles(): List<LlmProfile> =
        parseProfiles(context.agentDataStore.data.first()[kProfiles])

    /** 解出指定 profile 的明文 key（编辑时测试连接/获取模型在 key 留空复用原密钥） */
    suspend fun decryptKey(profileId: String): String? =
        listProfiles().firstOrNull { it.id == profileId }?.encKeyB64?.let { decB64(it) }

    /** 解出当前选中 profile 的明文配置；无可用配置返回 null（明文用完即弃，勿持久化） */
    suspend fun resolveActiveConfig(requireModel: Boolean = true): LlmConfig? {
        val p = context.agentDataStore.data.first()
        val list = parseProfiles(p[kProfiles])
        val activeId = p[kActiveId]
        val prof = list.firstOrNull { it.id == activeId } ?: list.firstOrNull() ?: return null
        val cfg = LlmConfig(
            provider = prof.provider,
            baseUrl = prof.baseUrl,
            apiKey = prof.encKeyB64?.let { decB64(it) }.orEmpty(),
            model = prof.model,
            rpmLimit = prof.rpmLimit
        )
        val ok = if (requireModel) cfg.isUsable else cfg.baseUrl.isNotBlank() && cfg.apiKey.isNotBlank()
        return cfg.takeIf { ok }
    }

    /**
     * 新增 / 更新一条配置。[plainKey]：null=保留原密钥，""=清空，其余=加密保存。
     * 首条保存时自动设为选中。
     */
    suspend fun upsertProfile(profile: LlmProfile, plainKey: String?) {
        context.agentDataStore.edit { p ->
            val list = parseProfiles(p[kProfiles]).toMutableList()
            val idx = list.indexOfFirst { it.id == profile.id }
            val enc = when {
                plainKey == null -> if (idx >= 0) list[idx].encKeyB64 else profile.encKeyB64
                plainKey.isEmpty() -> null
                else -> encB64(plainKey)
            }
            val finalProfile = profile.copy(
                name = profile.name.trim(),
                baseUrl = profile.baseUrl.trim(),
                model = profile.model.trim(),
                encKeyB64 = enc,
                updatedAt = System.currentTimeMillis()
            )
            if (idx >= 0) list[idx] = finalProfile else list.add(finalProfile)
            p[kProfiles] = json.encodeToString(list)
            if (p[kActiveId].isNullOrBlank()) p[kActiveId] = finalProfile.id
        }
    }

    suspend fun deleteProfile(id: String) {
        context.agentDataStore.edit { p ->
            val list = parseProfiles(p[kProfiles]).filterNot { it.id == id }
            p[kProfiles] = json.encodeToString(list)
            if (p[kActiveId] == id) {
                if (list.isNotEmpty()) p[kActiveId] = list.first().id else p.remove(kActiveId)
            }
        }
    }

    suspend fun setActive(id: String) {
        context.agentDataStore.edit { it[kActiveId] = id }
    }

    /** 读取当前确认策略（引擎每条命令前查一次，用户可中途在设置里改） */
    suspend fun confirmPolicyValue(): ConfirmPolicy =
        parsePolicy(context.agentDataStore.data.first()[kConfirmPolicy])

    suspend fun setConfirmPolicy(policy: ConfirmPolicy) {
        context.agentDataStore.edit { it[kConfirmPolicy] = policy.name }
    }

    private fun parsePolicy(s: String?): ConfirmPolicy =
        s?.let { runCatching { ConfirmPolicy.valueOf(it) }.getOrNull() } ?: ConfirmPolicy.DANGEROUS_ONLY

    /** WebDAV 同步用：当前选中 id */
    suspend fun activeProfileIdValue(): String? =
        context.agentDataStore.data.first()[kActiveId]

    /**
     * WebDAV 同步写回：用 [profiles]（encKeyB64 已是密文）整体镜像替换本地配置。
     * activeId 若有效则设为选中，否则保留/回退到首条。
     */
    suspend fun replaceProfiles(profiles: List<LlmProfile>, activeId: String?) {
        context.agentDataStore.edit { p ->
            p[kProfiles] = json.encodeToString(profiles)
            when {
                activeId != null && profiles.any { it.id == activeId } -> p[kActiveId] = activeId
                profiles.isNotEmpty() && (p[kActiveId] == null || profiles.none { it.id == p[kActiveId] }) ->
                    p[kActiveId] = profiles.first().id
                profiles.isEmpty() -> p.remove(kActiveId)
            }
        }
    }

    /** 明文 key → 加密 Base64（供同步写回时复用本类加密范式） */
    fun encryptKeyB64(plainKey: String): String = encB64(plainKey)

    private fun parseProfiles(s: String?): List<LlmProfile> =
        if (s.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<LlmProfile>>(s) }.getOrDefault(emptyList())

    private fun encB64(plain: String): String =
        Base64.encodeToString(CryptoManager.encryptString(plain), Base64.NO_WRAP)

    private fun decB64(b64: String): String =
        CryptoManager.decryptString(Base64.decode(b64, Base64.NO_WRAP))

    companion object {
        const val DEFAULT_CLAUDE_BASE = "https://api.anthropic.com"
        const val DEFAULT_OPENAI_BASE = "https://api.openai.com/v1"
    }
}
