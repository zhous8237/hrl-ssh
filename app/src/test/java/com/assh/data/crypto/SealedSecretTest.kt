package com.assh.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SealedSecret] 作用域/擦除语义测试（C6）。用 fake [SecretCodec] 替掉 Android Keystore，
 * 因此可在纯 JVM 上验证 useChars 用完清零、roundtrip 正确。
 */
class SealedSecretTest {

    /** 可逆的假编解码：encrypt = 前缀 + UTF-8 字节；decrypt = 去前缀。仅供测试。 */
    private object FakeCodec : SecretCodec {
        private val prefix = byteArrayOf(0x7F)
        override fun encryptString(plain: String): ByteArray =
            prefix + plain.toByteArray(Charsets.UTF_8)
        override fun decrypt(blob: ByteArray): ByteArray = blob.copyOfRange(1, blob.size)
    }

    @Test
    fun `seal then decryptToString roundtrips`() {
        val s = SealedSecret.seal("hunter2", FakeCodec)
        assertEquals("hunter2", s.decryptToString())
    }

    @Test
    fun `decryptBytes returns plaintext bytes`() {
        val s = SealedSecret.seal("abc", FakeCodec)
        assertArrayEquals("abc".toByteArray(Charsets.UTF_8), s.decryptBytes())
    }

    @Test
    fun `useChars exposes plaintext to block`() {
        val s = SealedSecret.seal("p@ssw0rd", FakeCodec)
        val seen = s.useChars { String(it) }
        assertEquals("p@ssw0rd", seen)
    }

    @Test
    fun `useChars wipes the buffer after block returns`() {
        val s = SealedSecret.seal("secret", FakeCodec)
        var leaked: CharArray? = null
        s.useChars { leaked = it }   // 故意把数组引用泄漏出来，验证已被清零
        assertNotEquals('s', leaked!![0])
        assertTrue("出作用域后缓冲应被清零为 NUL", leaked!!.all { it.code == 0 })
    }

    @Test
    fun `useChars returns block result`() {
        val s = SealedSecret.seal("12345", FakeCodec)
        val len = s.useChars { it.size }
        assertEquals(5, len)
    }

    @Test
    fun `useChars wipes even when block throws`() {
        val s = SealedSecret.seal("boom", FakeCodec)
        var leaked: CharArray? = null
        runCatching {
            s.useChars { leaked = it; throw RuntimeException("x") }
        }
        assertTrue("异常路径也应清零", leaked!!.all { it.code == 0 })
    }
}
