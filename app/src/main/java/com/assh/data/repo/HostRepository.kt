package com.assh.data.repo

import com.assh.data.crypto.CryptoManager
import com.assh.data.crypto.SealedSecret
import com.assh.data.db.AsshDatabase
import com.assh.data.db.entity.AuthType
import com.assh.data.db.entity.HostEntity
import com.assh.ssh.ResolvedHostConfig
import kotlinx.coroutines.flow.Flow

/** 主机配置仓库：敏感字段进出均过 CryptoManager（密文常驻，明文瞬时） */
class HostRepository(private val db: AsshDatabase) {

    fun observeAll(): Flow<List<HostEntity>> = db.hostDao().observeAll()

    fun observeById(id: Long): Flow<HostEntity?> = db.hostDao().observeById(id)

    suspend fun findById(id: Long): HostEntity? = db.hostDao().findById(id)

    /** 保存主机；password 传 null 表示不修改密码字段（编辑时未重输），传 "" 表示清空 */
    suspend fun save(host: HostEntity, plainPassword: String?): Long {
        val resolved = when {
            plainPassword == null -> host          // 保持原密文
            plainPassword.isEmpty() -> host.copy(encPassword = null)
            else -> host.copy(encPassword = CryptoManager.encryptString(plainPassword))
        }
        val now = System.currentTimeMillis()
        return if (resolved.id == 0L) {
            db.hostDao().insert(resolved.copy(updatedAt = now))
        } else {
            // 编辑时保留原 uuid（同步身份不能因再次保存而改变），刷新 updatedAt
            val keepUuid = db.hostDao().findById(resolved.id)?.uuid ?: resolved.uuid
            db.hostDao().update(resolved.copy(uuid = keepUuid, updatedAt = now))
            resolved.id
        }
    }

    suspend fun delete(host: HostEntity) = db.hostDao().delete(host)

    suspend fun touchLastConnected(id: Long) =
        db.hostDao().touchLastConnected(id, System.currentTimeMillis())

    /** 复制主机配置（操作单），密文字段原样复制；置顶显示便于立即看到 */
    suspend fun clone(host: HostEntity): Long =
        db.hostDao().insert(
            host.copy(
                id = 0,
                label = host.label + " (副本)",
                // 复制后排在原机附近且不沉底：沿用 sortOrder，时间戳设为当前
                sortOrder = host.sortOrder,
                lastConnectedAt = System.currentTimeMillis(),
                // 副本是独立实体，必须换新同步主键，否则与原机 uuid 冲突
                uuid = java.util.UUID.randomUUID().toString(),
                updatedAt = System.currentTimeMillis()
            )
        )

    /**
     * 解密组装连接配置。明文只在返回的 ResolvedHostConfig 中瞬时存在，
     * 连接建立后调用方应尽快丢弃引用。
     */
    suspend fun resolveForConnect(hostId: Long): ResolvedHostConfig {
        val host = db.hostDao().findById(hostId)
            ?: throw IllegalArgumentException("主机不存在: $hostId")

        var password: String? = null
        var privateKeyPem: String? = null

        when (host.authType) {
            AuthType.PASSWORD -> {
                // 优先用独立密码库引用（功能 4）；否则回退到内联 encPassword。
                // 经 SealedSecret 收口解密（C6）：sshj 的 authPassword(String) 强制 String 边界，
                // 故用 decryptToString 逃生口；明文随 ResolvedHostConfig 瞬时存在，连后即弃。
                password = when {
                    host.credentialId != null ->
                        db.credentialDao().findById(host.credentialId)
                            ?.let { SealedSecret(it.encPassword).decryptToString() }
                    else -> host.encPassword?.let { SealedSecret(it).decryptToString() }
                }
            }
            AuthType.KEY -> {
                val keyId = host.keyId ?: throw IllegalStateException("私钥认证但未选择私钥")
                val key = db.keyDao().findById(keyId)
                    ?: throw IllegalStateException("私钥不存在: $keyId")
                privateKeyPem = SealedSecret(key.encPrivateKey).decryptToString()
            }
        }

        return ResolvedHostConfig(
            hostId = host.id,
            label = host.label,
            host = host.host,
            port = host.port,
            username = host.username,
            authType = host.authType,
            password = password,
            privateKeyPem = privateKeyPem,
            charset = host.charset,
            initialCommand = host.initialCommand
        )
    }
}
