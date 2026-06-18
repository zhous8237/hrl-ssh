package com.assh.data.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore 硬件级字段加密（文档 §6）。
 *
 * - AES-256-GCM 主密钥常驻 Android Keystore（StrongBox 优先，降级 TEE），永不导出。
 * - 每条敏感数据单独加密，GCM 随机 12 字节 IV。
 * - 密文格式：[1B version][12B IV][ciphertext+tag]，整体作为 BLOB 存 Room。
 * - 原则：密文常驻，明文瞬时 —— 只在即将建立连接时解密，用完即弃。
 */
object CryptoManager : SecretCodec {
    private const val KEY_ALIAS = "assh_master_key"
    private const val ANDROID_KS = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val BLOB_VERSION: Byte = 1

    private val keyStore = KeyStore.getInstance(ANDROID_KS).apply { load(null) }

    /** App 启动时调用一次，确保主密钥存在 */
    fun ensureKey(context: Context) {
        if (keyStore.containsAlias(KEY_ALIAS)) return
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        // 不设 setUserAuthenticationRequired(true)，否则每次解密都要生物认证（§6.4 预留）

        val kpg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KS)

        // StrongBox 能力探测（API 28+），失败降级 TEE
        val hasStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        if (hasStrongBox) {
            try {
                builder.setIsStrongBoxBacked(true)
                kpg.init(builder.build())
                kpg.generateKey()
                return
            } catch (e: StrongBoxUnavailableException) {
                // 某些机型谎报 StrongBox，降级
                builder.setIsStrongBoxBacked(false)
            }
        }
        kpg.init(builder.build())
        kpg.generateKey()
    }

    private fun secretKey(): SecretKey =
        (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv                       // GCM 自动生成 12 字节
        val ct = cipher.doFinal(plain)
        return ByteBuffer.allocate(1 + iv.size + ct.size)
            .put(BLOB_VERSION).put(iv).put(ct).array()
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(blob)
        buf.get()                                // version，预留
        val iv = ByteArray(GCM_IV_LEN).also { buf.get(it) }
        val ct = ByteArray(buf.remaining()).also { buf.get(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    override fun encryptString(plain: String): ByteArray = encrypt(plain.toByteArray(Charsets.UTF_8))

    fun decryptString(blob: ByteArray): String = String(decrypt(blob), Charsets.UTF_8)
}
