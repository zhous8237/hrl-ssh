package com.assh.data.repo

import com.assh.data.crypto.CryptoManager
import com.assh.data.db.AsshDatabase
import com.assh.data.db.entity.KeyEntity
import kotlinx.coroutines.flow.Flow

/** 私钥仓库（功能 3）：导入即加密，列表永不解密 */
class KeyRepository(private val db: AsshDatabase) {

    fun observeAll(): Flow<List<KeyEntity>> = db.keyDao().observeAll()

    suspend fun findById(id: Long): KeyEntity? = db.keyDao().findById(id)

    /**
     * 导入私钥。privateKeyPem 为明文，入库前立即加密。
     * keyType 从 PEM 头部猜测，供 UI 展示。
     */
    suspend fun import(
        alias: String,
        privateKeyPem: String,
        sourceName: String? = null
    ): Long {
        require(alias.isNotBlank()) { "别名不能为空" }
        require(privateKeyPem.isNotBlank()) { "私钥内容不能为空" }
        if (db.keyDao().findByAlias(alias) != null) {
            throw IllegalArgumentException("别名已存在: $alias")
        }
        val entity = KeyEntity(
            alias = alias.trim(),
            keyType = guessKeyType(privateKeyPem),
            encPrivateKey = CryptoManager.encryptString(privateKeyPem),
            sourceName = sourceName?.takeIf { it.isNotBlank() }
        )
        return db.keyDao().insert(entity)
    }

    /** 删除前检查是否被主机引用 */
    suspend fun delete(key: KeyEntity): Boolean {
        if (db.hostDao().countByKeyId(key.id) > 0) return false
        db.keyDao().delete(key)
        return true
    }

    /**
     * 编辑私钥（功能 7）。privateKeyPem 传 null = 保持原密文不变；
     * 非空则重新加密覆盖。别名变更时校验唯一。
     */
    suspend fun update(
        key: KeyEntity,
        alias: String,
        privateKeyPem: String?,
        sourceName: String? = null
    ) {
        require(alias.isNotBlank()) { "别名不能为空" }
        val trimmed = alias.trim()
        if (trimmed != key.alias && db.keyDao().findByAlias(trimmed) != null) {
            throw IllegalArgumentException("别名已存在: $trimmed")
        }
        db.keyDao().update(
            key.copy(
                alias = trimmed,
                keyType = privateKeyPem?.let { guessKeyType(it) } ?: key.keyType,
                encPrivateKey = privateKeyPem?.let { CryptoManager.encryptString(it) }
                    ?: key.encPrivateKey,
                // 重新选了文件 → 更新来源；否则保持原值
                sourceName = sourceName?.takeIf { it.isNotBlank() } ?: key.sourceName,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun guessKeyType(pem: String): String {
        val head = pem.trim().lineSequence().take(2).joinToString(" ")
        return when {
            head.contains("OPENSSH PRIVATE KEY") -> "openssh"  // ed25519 等新格式
            head.contains("RSA PRIVATE KEY") -> "rsa"
            head.contains("EC PRIVATE KEY") -> "ecdsa"
            head.contains("DSA PRIVATE KEY") -> "dsa"
            head.contains("PRIVATE KEY") -> "pkcs8"
            else -> "unknown"
        }
    }
}
