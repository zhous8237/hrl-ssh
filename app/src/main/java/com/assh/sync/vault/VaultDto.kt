package com.assh.sync.vault

import kotlinx.serialization.Serializable

/**
 * 同步包结构（功能 7，开发文档 v2 §4.1）。
 *
 * 这是**与设备无关的明文结构**：序列化成 JSON 后整体用「同步口令」加密再上传，
 * 绝不含 v1 的 Keystore 密文。明文私钥/密码只在该 DTO 内存对象里短暂存在，
 * 加密上传后立即释放引用；导入解密后写回 Room 时立刻转回设备 Keystore 密文。
 *
 * 跨设备主键：每个实体用 [uuid] 匹配（非本地自增 id）。引用关系（host→key/credential、
 * command→host）一律用对方 uuid 表达，导入时再翻译回本地 id。
 */
@Serializable
data class VaultDto(
    val schema: Int = SCHEMA_VERSION,
    val vaultVersion: Long,            // 单调递增，每次成功推送 +1，用于并发检测
    val exportedAt: Long,
    val deviceId: String,              // 哪台设备最后写的
    val hosts: List<HostDto> = emptyList(),
    val keys: List<KeyDto> = emptyList(),
    val credentials: List<CredentialDto> = emptyList(),
    val commands: List<CommandDto> = emptyList(),
    val aiProfiles: List<AiProfileDto> = emptyList(),   // AI 模型配置（功能：WebDAV 备份 AI 配置）
    val aiActiveProfileId: String? = null
) {
    companion object {
        const val SCHEMA_VERSION = 2
    }
}

@Serializable
data class AiProfileDto(
    val id: String,
    val name: String,
    val provider: String,              // "OPENAI" / "CLAUDE"
    val baseUrl: String,
    val model: String,
    val apiKey: String? = null,        // 明文（整包加密保护）
    val updatedAt: Long,
    val deleted: Boolean = false
)

@Serializable
data class HostDto(
    val uuid: String,
    val label: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: String,              // "PASSWORD" / "KEY"
    val password: String? = null,      // 明文（整包会被加密），无内联密码则 null
    val keyRef: String? = null,        // 引用 KeyDto.uuid
    val credentialRef: String? = null, // 引用 CredentialDto.uuid
    val charset: String = "UTF-8",
    val initialCommand: String? = null,
    val sortOrder: Int = 0,
    val updatedAt: Long,
    val deleted: Boolean = false       // 墓碑位，前向兼容（本版本始终 false）
)

@Serializable
data class KeyDto(
    val uuid: String,
    val alias: String,
    val keyType: String,
    val privateKey: String,            // 明文 PEM/OpenSSH（整包加密保护）
    val sourceName: String? = null,
    val updatedAt: Long,
    val deleted: Boolean = false
)

@Serializable
data class CredentialDto(
    val uuid: String,
    val alias: String,
    val password: String,              // 明文（整包加密保护）
    val updatedAt: Long,
    val deleted: Boolean = false
)

@Serializable
data class CommandDto(
    val uuid: String,
    val label: String,
    val command: String,
    val appendEnter: Boolean = true,
    val hostRef: String? = null,       // 引用 HostDto.uuid（全局命令为 null）
    val sortOrder: Int = 0,
    val updatedAt: Long,
    val deleted: Boolean = false
)
