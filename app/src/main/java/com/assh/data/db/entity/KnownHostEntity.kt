package com.assh.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * host key 指纹（TOFU 信任模型，防 MITM）。
 *
 * 一条记录 = 某个 `host:port` 上的一把**已知合法**主机密钥。
 * 同一 `host:port` 允许存在多条（不同的 `fingerprintSha256`），用于适配：
 * - 负载均衡 / 多节点域名：域名背后多台机器，各自主机密钥不同
 * - 服务器轮换密钥：新旧密钥并存期间互不干扰
 *
 * 安全边界不变：只有“服务器这次出示的密钥命中任一已信任指纹”才放行；
 * 出现陌生密钥仍然抛 [com.assh.ssh.HostKeyChangedException] 交给用户决策。
 */
@Entity(
    tableName = "known_hosts",
    indices = [Index(value = ["hostPort"])]
)
data class KnownHostEntity(
    /** 复合主键：host:port#指纹，保证同一主机可存多把密钥 */
    @PrimaryKey val id: String,
    val hostPort: String,              // "host:port"
    val keyType: String,               // ssh-ed25519 等
    val fingerprintSha256: String,     // SHA256:base64 指纹
    val publicKeyBlob: ByteArray,
    /** 首次记录时间，用于 UI 展示与清理排序 */
    val addedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KnownHostEntity) return false
        return id == other.id && hostPort == other.hostPort && keyType == other.keyType &&
            fingerprintSha256 == other.fingerprintSha256 &&
            publicKeyBlob contentEquals other.publicKeyBlob
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + fingerprintSha256.hashCode()
        return result
    }

    companion object {
        /** 由 host:port 与指纹拼出复合主键 */
        fun makeId(hostPort: String, fingerprintSha256: String): String =
            "$hostPort#${fingerprintSha256}"
    }
}
