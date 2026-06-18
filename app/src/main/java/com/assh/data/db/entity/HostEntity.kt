package com.assh.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class AuthType { PASSWORD, KEY }

/** 服务器连接配置（功能 1） */
@Entity(tableName = "hosts")
data class HostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,               // 显示名，如 "生产 web01"
    val host: String,                // 域名或 IP
    val port: Int = 22,
    val username: String,
    val authType: AuthType,          // PASSWORD / KEY
    // 密码认证：加密后的密码；为空表示连接时再输入（旧版本/内联密码）
    val encPassword: ByteArray? = null,
    // 密码认证：引用 CredentialEntity.id（功能 4，独立密码库别名选择）；为空表示用内联 encPassword
    val credentialId: Long? = null,
    // 私钥认证：引用 KeyEntity.id（功能 3，别名选择）
    val keyId: Long? = null,
    // 终端首选项
    val charset: String = "UTF-8",
    val initialCommand: String? = null,  // 登录后自动执行
    val sortOrder: Int = 0,
    val lastConnectedAt: Long? = null,   // 用于"最近连接"排序
    // —— v2 同步字段（功能 7）——
    val uuid: String = UUID.randomUUID().toString(), // 跨设备稳定主键
    val updatedAt: Long = System.currentTimeMillis() // LWW 合并时间戳
) {
    // 含 ByteArray 的 data class 需手写 equals/hashCode
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HostEntity) return false
        return id == other.id && label == other.label && host == other.host &&
            port == other.port && username == other.username && authType == other.authType &&
            (encPassword contentEquals other.encPassword) &&
            credentialId == other.credentialId && keyId == other.keyId &&
            charset == other.charset && initialCommand == other.initialCommand &&
            sortOrder == other.sortOrder && lastConnectedAt == other.lastConnectedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + host.hashCode()
        result = 31 * result + port
        result = 31 * result + username.hashCode()
        result = 31 * result + authType.hashCode()
        result = 31 * result + (encPassword?.contentHashCode() ?: 0)
        result = 31 * result + (keyId?.hashCode() ?: 0)
        return result
    }
}
