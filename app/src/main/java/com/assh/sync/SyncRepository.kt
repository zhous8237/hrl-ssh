package com.assh.sync

import androidx.room.withTransaction
import com.assh.ai.AgentPreferences
import com.assh.ai.llm.LlmProfile
import com.assh.ai.llm.LlmProvider
import com.assh.data.crypto.CryptoManager
import com.assh.data.db.AsshDatabase
import com.assh.data.db.entity.AuthType
import com.assh.data.db.entity.CommandEntity
import com.assh.data.db.entity.CredentialEntity
import com.assh.data.db.entity.HostEntity
import com.assh.data.db.entity.KeyEntity
import com.assh.sync.vault.AiProfileDto
import com.assh.sync.vault.CommandDto
import com.assh.sync.vault.CredentialDto
import com.assh.sync.vault.HostDto
import com.assh.sync.vault.KeyDto
import com.assh.sync.vault.VaultDto

/**
 * Room ↔ VaultDto 转换与写回（功能 7，开发文档 v2 §2.2/§4.2）。
 *
 * - 导出：Room 实体经设备 Keystore 解密 → 明文 DTO（本地自增 id 翻译成对方 uuid 引用）。
 * - 写回：DTO 明文敏感字段立即用设备 Keystore 重新加密入库；uuid 引用翻译回本地 id。
 * - [applyMirror] 让本地成为 DTO 的精确镜像（PULL / 合并后写回都走它）。
 */
class SyncRepository(
    private val db: AsshDatabase,
    private val agentPreferences: AgentPreferences
) {

    // ===== 导出 =====

    suspend fun exportToDto(vaultVersion: Long, deviceId: String): VaultDto {
        val base = db.withTransaction {
        val keys = db.keyDao().getAll()
        val creds = db.credentialDao().getAll()
        val hosts = db.hostDao().getAll()
        val commands = db.commandDao().getAll()

        val keyIdToUuid = keys.associate { it.id to it.uuid }
        val credIdToUuid = creds.associate { it.id to it.uuid }
        val hostIdToUuid = hosts.associate { it.id to it.uuid }

        VaultDto(
            vaultVersion = vaultVersion,
            exportedAt = System.currentTimeMillis(),
            deviceId = deviceId,
            keys = keys.map {
                KeyDto(
                    uuid = it.uuid, alias = it.alias, keyType = it.keyType,
                    privateKey = CryptoManager.decryptString(it.encPrivateKey),
                    sourceName = it.sourceName, updatedAt = it.updatedAt
                )
            },
            credentials = creds.map {
                CredentialDto(
                    uuid = it.uuid, alias = it.alias,
                    password = CryptoManager.decryptString(it.encPassword),
                    updatedAt = it.updatedAt
                )
            },
            hosts = hosts.map {
                HostDto(
                    uuid = it.uuid, label = it.label, host = it.host, port = it.port,
                    username = it.username, authType = it.authType.name,
                    password = it.encPassword?.let { p -> CryptoManager.decryptString(p) },
                    keyRef = it.keyId?.let { id -> keyIdToUuid[id] },
                    credentialRef = it.credentialId?.let { id -> credIdToUuid[id] },
                    charset = it.charset, initialCommand = it.initialCommand,
                    sortOrder = it.sortOrder, updatedAt = it.updatedAt
                )
            },
            commands = commands.map {
                CommandDto(
                    uuid = it.uuid, label = it.label, command = it.command,
                    appendEnter = it.appendEnter,
                    hostRef = it.hostId?.let { id -> hostIdToUuid[id] },
                    sortOrder = it.sortOrder, updatedAt = it.updatedAt
                )
            }
        )
        }
        // AI 模型配置（DataStore，事务外读取）；key 解密成明文，整包会被同步口令加密
        val aiDtos = agentPreferences.listProfiles().map { p ->
            AiProfileDto(
                id = p.id, name = p.name, provider = p.provider.name,
                baseUrl = p.baseUrl, model = p.model,
                apiKey = agentPreferences.decryptKey(p.id),
                updatedAt = p.updatedAt
            )
        }
        return base.copy(aiProfiles = aiDtos, aiActiveProfileId = agentPreferences.activeProfileIdValue())
    }

    /** 当前本地实体计数（PUSH 后报告用） */
    suspend fun localStats(): SyncStats = SyncStats(
        hosts = db.hostDao().getAll().size,
        keys = db.keyDao().getAll().size,
        credentials = db.credentialDao().getAll().size,
        commands = db.commandDao().getAll().size,
        aiProfiles = agentPreferences.listProfiles().size
    )

    // ===== 合并（LWW） =====

    /**
     * 三方合并：委托给纯函数 [VaultMerger]（C3 深化，逻辑已抽出以便单测）。
     * 保留此方法仅为兼容现有调用点。
     */
    fun merge(local: VaultDto, remote: VaultDto, vaultVersion: Long, deviceId: String): VaultDto =
        VaultMerger.merge(local, remote, vaultVersion, deviceId)

    // ===== 写回（镜像） =====

    /**
     * 让本地数据成为 [dto] 的精确镜像：
     * - dto 中存在的实体按 uuid upsert（敏感字段重新设备加密；引用 uuid 翻译回本地 id）。
     * - 本地有、dto 没有的实体删除（保证 PULL 能传播云端删除）。
     * 返回本次实际变更（增/改/删）计数。
     */
    suspend fun applyMirror(dto: VaultDto): SyncStats {
        val dbStats = db.withTransaction {
        val now = System.currentTimeMillis()
        var keyChanges = 0
        var credChanges = 0
        var hostChanges = 0
        var cmdChanges = 0

        val liveKeys = dto.keys.filterNot { it.deleted }
        val liveCreds = dto.credentials.filterNot { it.deleted }
        val liveHosts = dto.hosts.filterNot { it.deleted }
        val liveCmds = dto.commands.filterNot { it.deleted }

        // 1) 删除本地多余项（无 FK 约束，删除顺序无关）
        val keepKeyUuids = liveKeys.mapTo(HashSet()) { it.uuid }
        db.keyDao().getAll().forEach { if (it.uuid !in keepKeyUuids) { db.keyDao().delete(it); keyChanges++ } }
        val keepCredUuids = liveCreds.mapTo(HashSet()) { it.uuid }
        db.credentialDao().getAll().forEach { if (it.uuid !in keepCredUuids) { db.credentialDao().delete(it); credChanges++ } }
        val keepHostUuids = liveHosts.mapTo(HashSet()) { it.uuid }
        db.hostDao().getAll().forEach { if (it.uuid !in keepHostUuids) { db.hostDao().delete(it); hostChanges++ } }
        val keepCmdUuids = liveCmds.mapTo(HashSet()) { it.uuid }
        db.commandDao().getAll().forEach { if (it.uuid !in keepCmdUuids) { db.commandDao().delete(it); cmdChanges++ } }

        // 2) upsert keys / credentials，建立 uuid→本地 id 映射
        val keyMap = HashMap<String, Long>()
        for (k in liveKeys) {
            val enc = CryptoManager.encryptString(k.privateKey)
            val local = db.keyDao().findByUuid(k.uuid)
            val alias = freeKeyAlias(k.alias, k.uuid)
            if (local != null) {
                if (changed(local.alias != alias, local.updatedAt != k.updatedAt)) keyChanges++
                db.keyDao().update(
                    local.copy(alias = alias, keyType = k.keyType, encPrivateKey = enc,
                        sourceName = k.sourceName, updatedAt = k.updatedAt)
                )
                keyMap[k.uuid] = local.id
            } else {
                val id = db.keyDao().insert(
                    KeyEntity(alias = alias, keyType = k.keyType, encPrivateKey = enc,
                        sourceName = k.sourceName, createdAt = now, uuid = k.uuid, updatedAt = k.updatedAt)
                )
                keyMap[k.uuid] = id; keyChanges++
            }
        }

        val credMap = HashMap<String, Long>()
        for (c in liveCreds) {
            val enc = CryptoManager.encryptString(c.password)
            val local = db.credentialDao().findByUuid(c.uuid)
            val alias = freeCredAlias(c.alias, c.uuid)
            if (local != null) {
                if (local.alias != alias || local.updatedAt != c.updatedAt) credChanges++
                db.credentialDao().update(
                    local.copy(alias = alias, encPassword = enc, updatedAt = c.updatedAt)
                )
                credMap[c.uuid] = local.id
            } else {
                val id = db.credentialDao().insert(
                    CredentialEntity(alias = alias, encPassword = enc, createdAt = now,
                        uuid = c.uuid, updatedAt = c.updatedAt)
                )
                credMap[c.uuid] = id; credChanges++
            }
        }

        // 3) upsert hosts（引用翻译回本地 id；引用缺失则置空）
        val hostMap = HashMap<String, Long>()
        for (h in liveHosts) {
            val enc = h.password?.let { CryptoManager.encryptString(it) }
            val keyId = h.keyRef?.let { keyMap[it] }
            val credId = h.credentialRef?.let { credMap[it] }
            val authType = runCatching { AuthType.valueOf(h.authType) }.getOrDefault(AuthType.PASSWORD)
            val local = db.hostDao().findByUuid(h.uuid)
            if (local != null) {
                if (local.updatedAt != h.updatedAt) hostChanges++
                db.hostDao().update(
                    local.copy(
                        label = h.label, host = h.host, port = h.port, username = h.username,
                        authType = authType, encPassword = enc, credentialId = credId, keyId = keyId,
                        charset = h.charset, initialCommand = h.initialCommand,
                        sortOrder = h.sortOrder, updatedAt = h.updatedAt
                    )
                )
                hostMap[h.uuid] = local.id
            } else {
                val id = db.hostDao().insert(
                    HostEntity(
                        label = h.label, host = h.host, port = h.port, username = h.username,
                        authType = authType, encPassword = enc, credentialId = credId, keyId = keyId,
                        charset = h.charset, initialCommand = h.initialCommand,
                        sortOrder = h.sortOrder, lastConnectedAt = null,
                        uuid = h.uuid, updatedAt = h.updatedAt
                    )
                )
                hostMap[h.uuid] = id; hostChanges++
            }
        }

        // 4) upsert commands（hostRef 翻译）
        for (cmd in liveCmds) {
            val hostId = cmd.hostRef?.let { hostMap[it] }
            val local = db.commandDao().findByUuid(cmd.uuid)
            if (local != null) {
                if (local.updatedAt != cmd.updatedAt) cmdChanges++
                db.commandDao().update(
                    local.copy(label = cmd.label, command = cmd.command, appendEnter = cmd.appendEnter,
                        hostId = hostId, sortOrder = cmd.sortOrder, updatedAt = cmd.updatedAt)
                )
            } else {
                db.commandDao().insert(
                    CommandEntity(label = cmd.label, command = cmd.command, appendEnter = cmd.appendEnter,
                        hostId = hostId, sortOrder = cmd.sortOrder, uuid = cmd.uuid, updatedAt = cmd.updatedAt)
                )
                cmdChanges++
            }
        }

        SyncStats(hosts = hostChanges, keys = keyChanges, credentials = credChanges, commands = cmdChanges)
        }
        // AI 模型配置写回（DataStore，事务外）：明文 key 重新设备 Keystore 加密；整体镜像替换。
        // 变更数与上面四类口径一致（本次新增/修改/删除计数），而非配置总数 —— 否则本地与
        // 云端已一致时仍会误显示「AI 4」让人以为同步了 4 条。
        val aiLive = dto.aiProfiles.filterNot { it.deleted }
        val beforeProfiles = agentPreferences.listProfiles()
        val beforeById = beforeProfiles.associateBy { it.id }
        val liveIds = aiLive.mapTo(HashSet()) { it.id }
        var aiChanges = beforeProfiles.count { it.id !in liveIds }   // 删除：本地有、云端没有
        for (a in aiLive) {                                          // 新增 / 修改
            val old = beforeById[a.id]
            if (old == null || old.updatedAt != a.updatedAt) aiChanges++
        }
        val profiles = aiLive.map {
            LlmProfile(
                id = it.id, name = it.name,
                provider = runCatching { LlmProvider.valueOf(it.provider) }.getOrDefault(LlmProvider.OPENAI),
                baseUrl = it.baseUrl, model = it.model,
                encKeyB64 = it.apiKey?.takeIf { k -> k.isNotBlank() }?.let { k -> agentPreferences.encryptKeyB64(k) },
                updatedAt = it.updatedAt
            )
        }
        agentPreferences.replaceProfiles(profiles, dto.aiActiveProfileId)
        return dbStats.copy(aiProfiles = aiChanges)
    }

    private fun changed(vararg flags: Boolean) = flags.any { it }

    /** 返回一个不与「别的」私钥别名冲突的可用别名（同 uuid 视为自己） */
    private suspend fun freeKeyAlias(desired: String, selfUuid: String): String {
        var candidate = desired.ifBlank { "key" }
        var n = 2
        while (true) {
            val row = db.keyDao().findByAlias(candidate)
            if (row == null || row.uuid == selfUuid) return candidate
            candidate = "${desired} ($n)"; n++
        }
    }

    private suspend fun freeCredAlias(desired: String, selfUuid: String): String {
        var candidate = desired.ifBlank { "credential" }
        var n = 2
        while (true) {
            val row = db.credentialDao().findByAlias(candidate)
            if (row == null || row.uuid == selfUuid) return candidate
            candidate = "${desired} ($n)"; n++
        }
    }
}
