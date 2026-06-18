package com.assh.ssh

import com.assh.data.db.dao.KnownHostDao
import com.assh.data.db.entity.KnownHostEntity
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey

/** host key 变更时抛出，UI 捕获后弹窗让用户决策（可能是 MITM，也可能是服务器重装） */
class HostKeyChangedException(
    val hostPort: String,
    val savedFingerprint: String,
    val actualFingerprint: String,
    val actualKeyType: String,
    val actualKeyBlob: ByteArray
) : SecurityException(
    "服务器 $hostPort 的 host key 已变更！保存的指纹: $savedFingerprint, 当前指纹: $actualFingerprint"
)

/**
 * TOFU 信任模型（文档 §7.3）：
 * - 首次连接：记录指纹到 known_hosts 表，信任。
 * - 再次连接：比对指纹；不一致抛 HostKeyChangedException，绝不静默接受。
 */
class AsshHostKeyVerifier(private val dao: KnownHostDao) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val hostPort = "$hostname:$port"
        val fingerprint = SecurityUtils.getFingerprint(key)
        val keyType = KeyType.fromKey(key).toString()
        val keyBlob = key.encoded ?: ByteArray(0)

        val saved = dao.findBlocking(hostPort)
        return when {
            saved == null -> {
                // TOFU 首次信任
                dao.insertBlocking(
                    KnownHostEntity(
                        hostPort = hostPort,
                        keyType = keyType,
                        fingerprintSha256 = fingerprint,
                        publicKeyBlob = keyBlob
                    )
                )
                true
            }
            saved.fingerprintSha256 == fingerprint -> true
            else -> throw HostKeyChangedException(
                hostPort = hostPort,
                savedFingerprint = saved.fingerprintSha256,
                actualFingerprint = fingerprint,
                actualKeyType = keyType,
                actualKeyBlob = keyBlob
            )
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
        val saved = dao.findBlocking("$hostname:$port") ?: return emptyList()
        return listOf(saved.keyType)
    }
}
