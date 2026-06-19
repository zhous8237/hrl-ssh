package com.assh.ai.ssh

import com.assh.ssh.HostKeyChangedException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * [SshErrorClassifier] 表驱动测试（仿 DangerousCommandDetectorTest）：
 * 传输/网络错→TRANSIENT，认证/密钥/算法/指纹→FATAL，未知→TRANSIENT，cause 链遍历命中。
 */
class SshErrorClassifierTest {

    private fun kind(e: Throwable) = SshErrorClassifier.classify(e)

    @Test
    fun `transport and network errors are transient`() {
        val transient = listOf(
            IOException("Connection reset by peer"),
            EOFException("Server closed connection during identification exchange"),
            SocketTimeoutException("Read timed out"),
            IOException("Broken pipe"),
            RuntimeException("Connection refused"),
            RuntimeException("No route to host"),
            RuntimeException("Network is unreachable"),
            IllegalStateException("SSH 连接已断开"),
            SshReconnectFailedException("重连预算耗尽")
        )
        for (e in transient) assertEquals("应判可重连: ${e.message}", SshErrorKind.TRANSIENT, kind(e))
    }

    @Test
    fun `auth key and negotiation errors are fatal`() {
        val fatal = listOf(
            RuntimeException("Exhausted available authentication methods"),
            RuntimeException("Permission denied (publickey,password)"),
            RuntimeException("Too many authentication failures"),
            RuntimeException("Could not parse privatekey"),
            RuntimeException("passphrase required to decrypt key"),
            RuntimeException("Could not negotiate: no common key exchange algorithm"),
            IllegalStateException("密码未提供"),
            IllegalStateException("私钥未提供")
        )
        for (e in fatal) assertEquals("应判致命: ${e.message}", SshErrorKind.FATAL, kind(e))
    }

    @Test
    fun `host key change is fatal`() {
        val e = HostKeyChangedException("h:22", "aa", "bb", "ssh-ed25519", ByteArray(0))
        assertEquals(SshErrorKind.FATAL, kind(e))
    }

    @Test
    fun `walks cause chain for fatal root`() {
        val wrapped = IOException("transport error", RuntimeException("Auth fail"))
        assertEquals(SshErrorKind.FATAL, kind(wrapped))
    }

    @Test
    fun `walks cause chain for transient root`() {
        val wrapped = RuntimeException("session broke", EOFException("eof"))
        assertEquals(SshErrorKind.TRANSIENT, kind(wrapped))
    }

    @Test
    fun `unknown defaults to transient`() {
        assertEquals(SshErrorKind.TRANSIENT, kind(RuntimeException("something weird")))
    }

    @Test
    fun `deep cause chain terminates without error`() {
        var e: Throwable = RuntimeException("root")
        repeat(50) { e = RuntimeException("layer", e) }
        assertEquals(SshErrorKind.TRANSIENT, kind(e))
    }
}
