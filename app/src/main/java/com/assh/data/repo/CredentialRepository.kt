package com.assh.data.repo

import com.assh.data.crypto.CryptoManager
import com.assh.data.db.AsshDatabase
import com.assh.data.db.entity.CredentialEntity
import kotlinx.coroutines.flow.Flow

/** 密码凭据仓库（功能 4）：导入即加密，列表永不解密；通过别名在主机配置中引用 */
class CredentialRepository(private val db: AsshDatabase) {

    fun observeAll(): Flow<List<CredentialEntity>> = db.credentialDao().observeAll()

    suspend fun findById(id: Long): CredentialEntity? = db.credentialDao().findById(id)

    /** 解密取出明文密码（仅在即将连接时调用） */
    suspend fun resolvePassword(id: Long): String? =
        db.credentialDao().findById(id)?.let { CryptoManager.decryptString(it.encPassword) }

    /** 新增密码凭据。password 明文，入库前立即加密 */
    suspend fun import(alias: String, password: String): Long {
        require(alias.isNotBlank()) { "别名不能为空" }
        require(password.isNotBlank()) { "密码不能为空" }
        if (db.credentialDao().findByAlias(alias.trim()) != null) {
            throw IllegalArgumentException("别名已存在: ${alias.trim()}")
        }
        val entity = CredentialEntity(
            alias = alias.trim(),
            encPassword = CryptoManager.encryptString(password)
        )
        return db.credentialDao().insert(entity)
    }

    /** 删除前检查是否被主机引用 */
    suspend fun delete(credential: CredentialEntity): Boolean {
        if (db.hostDao().countByCredentialId(credential.id) > 0) return false
        db.credentialDao().delete(credential)
        return true
    }

    /**
     * 编辑密码凭据。password 传 null = 保持原密文不变；非空则重新加密覆盖。
     * 别名变更时校验唯一。
     */
    suspend fun update(
        credential: CredentialEntity,
        alias: String,
        password: String?
    ) {
        require(alias.isNotBlank()) { "别名不能为空" }
        val trimmed = alias.trim()
        if (trimmed != credential.alias && db.credentialDao().findByAlias(trimmed) != null) {
            throw IllegalArgumentException("别名已存在: $trimmed")
        }
        db.credentialDao().update(
            credential.copy(
                alias = trimmed,
                encPassword = password?.takeIf { it.isNotBlank() }
                    ?.let { CryptoManager.encryptString(it) }
                    ?: credential.encPassword,
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}
