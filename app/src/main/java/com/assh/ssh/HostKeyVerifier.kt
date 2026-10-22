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

/** sshj 会包装校验异常；限深遍历原因链，避免循环引用。 */
fun Throwable.hostKeyChange(): HostKeyChangedException? =
    generateSequence(this) { it.cause }.take(16).filterIsInstance<HostKeyChangedException>().firstOrNull()

/**
 * TOFU 信任模型（文档 §7.3）：
 * - 首次连接：记录指纹到 known_hosts 表，信任。
 * - 再次连接：与**该主机已信任的全部指纹**逐一比对，命中任一即放行。
 *
 * 为什么允许多条：域名后面可能挂多台机器（负载均衡），各自主机密钥不同；
 * 服务器轮换密钥时新旧也可能并存。只认一条会导致“每次连到不同后端都弹窗”。
 * 安全边界不变：出现**陌生**密钥仍然抛异常交给用户决策。
 */
class AsshHostKeyVerifier(private val dao: KnownHostDao) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val hostPort = "$hostname:$port"
        val fingerprint = SecurityUtils.getFingerprint(key)
        val keyType = KeyType.fromKey(key).toString()
        val keyBlob = key.encoded ?: ByteArray(0)

        val saved = dao.findAllBlocking(hostPort)
        return when {
            saved.isEmpty() -> {
                // TOFU 首次信任
                remember(hostPort, keyType, fingerprint, keyBlob)
                true
            }
            saved.any { it.fingerprintSha256 == fingerprint } -> true
            else -> throw HostKeyChangedException(
                hostPort = hostPort,
                savedFingerprint = saved.joinToString("、") { it.fingerprintSha256 },
                actualFingerprint = fingerprint,
                actualKeyType = keyType,
                actualKeyBlob = keyBlob
            )
        }
    }

    /**
     * 记录一把新密钥。
     *
     * 用户确认“信任新指纹”时会走这里：**不删除**旧指纹，改为追加，
     * 这样同一主机在多个后端之间轮转时不会互相顶掉。
     */
    fun remember(hostPort: String, keyType: String, fingerprint: String, keyBlob: ByteArray) {
        dao.insertBlocking(
            KnownHostEntity(
                id = KnownHostEntity.makeId(hostPort, fingerprint),
                hostPort = hostPort,
                keyType = keyType,
                fingerprintSha256 = fingerprint,
                publicKeyBlob = keyBlob
            )
        )
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
        val saved = dao.findAllBlocking("$hostname:$port")
        // 已记录的所有 keyType 都视为“已知算法”，避免重复试探
        return saved.map { it.keyType }.distinct()
    }
}
