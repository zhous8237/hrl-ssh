package com.assh.ssh

import com.assh.data.db.entity.AuthType

/**
 * 解密后的瞬时连接配置（文档 §7.2）。
 * 含明文凭据，只在「即将建立连接」时存在，连接建立后调用方尽快丢弃引用，不得持久化、不得写日志。
 */
data class ResolvedHostConfig(
    val hostId: Long,
    val label: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: AuthType,
    val password: String?,        // PASSWORD 认证；null = 配置未存密码，连接时再问
    val privateKeyPem: String?,   // KEY 认证
    val charset: String = "UTF-8",
    val initialCommand: String? = null
) {
    override fun toString(): String =
        "ResolvedHostConfig(hostId=$hostId, $username@$host:$port, auth=$authType)" // 不泄露凭据
}
