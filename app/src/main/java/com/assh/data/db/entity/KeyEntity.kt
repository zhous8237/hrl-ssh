package com.assh.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** 可复用私钥（功能 3），通过别名在主机配置中选择 */
@Entity(tableName = "keys", indices = [Index(value = ["alias"], unique = true)])
data class KeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alias: String,                    // 别名，UI 选择用，唯一
    val keyType: String,                  // "ed25519" / "rsa" / "ecdsa"（展示用）
    val encPrivateKey: ByteArray,         // 加密后的私钥 PEM/OpenSSH 文本
    val sourceName: String? = null,       // 私钥来源文件名（选择文件导入时记录，编辑时回显）
    val createdAt: Long = System.currentTimeMillis(),
    // —— v2 同步字段（功能 7）——
    val uuid: String = UUID.randomUUID().toString(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyEntity) return false
        return id == other.id && alias == other.alias && keyType == other.keyType &&
            encPrivateKey contentEquals other.encPrivateKey &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + alias.hashCode()
        result = 31 * result + encPrivateKey.contentHashCode()
        return result
    }
}
