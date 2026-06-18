package com.assh.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** host key 指纹（TOFU 信任模型，防 MITM） */
@Entity(tableName = "known_hosts")
data class KnownHostEntity(
    @PrimaryKey val hostPort: String,  // "host:port"
    val keyType: String,               // ssh-ed25519 等
    val fingerprintSha256: String,     // SHA256:base64 指纹
    val publicKeyBlob: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KnownHostEntity) return false
        return hostPort == other.hostPort && keyType == other.keyType &&
            fingerprintSha256 == other.fingerprintSha256 &&
            publicKeyBlob contentEquals other.publicKeyBlob
    }

    override fun hashCode(): Int {
        var result = hostPort.hashCode()
        result = 31 * result + fingerprintSha256.hashCode()
        return result
    }
}
