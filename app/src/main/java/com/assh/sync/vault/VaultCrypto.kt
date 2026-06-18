package com.assh.sync.vault

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 同步口令错误（解密失败，GCM tag 校验不过） */
class WrongPassphraseException : Exception("同步口令错误，无法解密云端备份")

/** 同步包格式损坏 / 非本 App 产物 */
class VaultFormatException(msg: String) : Exception(msg)

/**
 * 同步包端到端加密（功能 7，开发文档 v2 §4.4）。
 *
 * 密钥派生：用户「同步口令」→ Argon2id → 32 字节 AES key。
 * 关键：派生密钥**不绑定设备 Keystore**，换设备输入同一口令即可解密。
 *
 * 包体格式：
 * ```
 * [magic "ASV1"(4)] [saltLen=16(1)] [salt(16)] [iv(12)] [ciphertext+GCM tag]
 * ```
 * salt 随包走（每次加密新生成），口令本身绝不入包。
 */
object VaultCrypto {
    private val MAGIC = byteArrayOf('A'.code.toByte(), 'S'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val KEY_LEN = 32
    private const val GCM_TAG_BITS = 128

    // Argon2id 参数：抗暴力。低端机 64MB 可能偏慢，必要时可下调 memory。
    private const val ARGON2_ITERATIONS = 3
    private const val ARGON2_MEMORY_KB = 64 * 1024 // 64 MB
    private const val ARGON2_PARALLELISM = 2

    private val rng = SecureRandom()

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val gen = Argon2BytesGenerator()
        gen.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withIterations(ARGON2_ITERATIONS)
                .withMemoryAsKB(ARGON2_MEMORY_KB)
                .withParallelism(ARGON2_PARALLELISM)
                .build()
        )
        val out = ByteArray(KEY_LEN)
        gen.generateBytes(passphrase, out)
        return SecretKeySpec(out, "AES")
    }

    fun encrypt(plain: ByteArray, passphrase: CharArray): ByteArray {
        val salt = ByteArray(SALT_LEN).also { rng.nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv                     // GCM 自动生成 12 字节
        val ct = cipher.doFinal(plain)
        return MAGIC + byteArrayOf(SALT_LEN.toByte()) + salt + iv + ct
    }

    fun decrypt(blob: ByteArray, passphrase: CharArray): ByteArray {
        if (blob.size < MAGIC.size + 1 + SALT_LEN + IV_LEN) {
            throw VaultFormatException("同步包过短或已损坏")
        }
        var off = 0
        val magic = blob.copyOfRange(0, MAGIC.size); off += MAGIC.size
        if (!magic.contentEquals(MAGIC)) throw VaultFormatException("无法识别的同步包格式")
        val saltLen = blob[off].toInt() and 0xFF; off += 1
        if (saltLen != SALT_LEN) throw VaultFormatException("同步包 salt 长度异常")
        val salt = blob.copyOfRange(off, off + saltLen); off += saltLen
        val iv = blob.copyOfRange(off, off + IV_LEN); off += IV_LEN
        val ct = blob.copyOfRange(off, blob.size)

        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return try {
            cipher.doFinal(ct)
        } catch (e: AEADBadTagException) {
            throw WrongPassphraseException()
        }
    }
}
