package com.assh.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 可复用密码凭据（独立密码库），通过别名在主机配置中选择。
 * 与 KeyEntity 对称：明文密码加密后入库，列表永不解密。
 */
@Entity(tableName = "credentials", indices = [Index(value = ["alias"], unique = true)])
data class CredentialEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alias: String,              // 别名，UI 选择用，唯一
    val encPassword: ByteArray,     // 加密后的密码
    val createdAt: Long = System.currentTimeMillis(),
    // —— v2 同步字段（功能 7）——
    val uuid: String = UUID.randomUUID().toString(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CredentialEntity) return false
        return id == other.id && alias == other.alias &&
            encPassword contentEquals other.encPassword && createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + alias.hashCode()
        result = 31 * result + encPassword.contentHashCode()
        return result
    }
}
