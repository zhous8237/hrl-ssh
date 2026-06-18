package com.assh.data.crypto

/**
 * 加解密原语 seam（C6 深化）：把 [CryptoManager] 的能力抽成接口，
 * 让 [SealedSecret] 的擦除/作用域语义能用 fake codec 在 JVM 上单测（不依赖 Android Keystore）。
 */
interface SecretCodec {
    fun encryptString(plain: String): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * 受控密文（C6 深化）：包裹一段 Keystore 密文，把"解密 -> 用 -> 擦除"收在一个作用域里，
 * 让"明文是瞬时的"从注释变成可执行的约束。
 *
 * 现实约束诚实说明：sshj 的 authPassword(String)/loadKeys(String) 与 @Serializable DTO 仍要求
 * String，那条边界上的明文无法擦除（[decryptToString] 是显式逃生口）。[useChars] 用于真正能
 * 用完即弃的路径，出作用域即把 CharArray 清零（填 NUL）。
 */
class SealedSecret(
    private val blob: ByteArray,
    private val codec: SecretCodec = CryptoManager
) {
    /** 解密为字节；调用方负责处置（一般用 [useChars] 而非直接拿走） */
    fun decryptBytes(): ByteArray = codec.decrypt(blob)

    /**
     * 在作用域内拿到明文 CharArray，块返回后立即把数组清零（中间字节也清零）。
     * 适合"解密 -> 立刻消费 -> 丢弃"的路径。清零值用 NUL。
     */
    fun <R> useChars(block: (CharArray) -> R): R {
        val bytes = codec.decrypt(blob)
        val text = String(bytes, Charsets.UTF_8)
        bytes.fill(0)
        val buf = text.toCharArray()
        return try {
            block(buf)
        } finally {
            buf.fill(WIPE)
        }
    }

    /** 不可擦除的逃生口：仅用于 sshj / 序列化等强制要求 String 的边界。 */
    fun decryptToString(): String = String(codec.decrypt(blob), Charsets.UTF_8)

    companion object {
        private val WIPE = Char(0)

        /** 把明文加密成一个 SealedSecret（便于"即收即封"） */
        fun seal(plain: String, codec: SecretCodec = CryptoManager): SealedSecret =
            SealedSecret(codec.encryptString(plain), codec)
    }
}
