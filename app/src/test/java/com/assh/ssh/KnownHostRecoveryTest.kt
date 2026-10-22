package com.assh.ssh

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.assh.data.db.AsshDatabase
import com.assh.data.db.entity.AuthType
import com.assh.data.db.entity.CredentialEntity
import com.assh.data.db.entity.HostEntity
import com.assh.data.db.entity.KeyEntity
import com.assh.data.db.entity.KnownHostEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.security.KeyPairGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class KnownHostRecoveryTest {

    private fun db() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), AsshDatabase::class.java
    ).allowMainThreadQueries().build()

    private fun rsaKey() = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(2048) }.generateKeyPair().public

    @Test
    fun `首次记录匹配通过陌生密钥失败清空后重新记录且保留其他表`() = runBlocking {
        val db = db()
        try {
            val keyId = db.keyDao().insert(KeyEntity(alias = "测试私钥", keyType = "rsa", encPrivateKey = byteArrayOf(1)))
            val credentialId = db.credentialDao().insert(CredentialEntity(alias = "测试密码", encPassword = byteArrayOf(2)))
            val hostId = db.hostDao().insert(HostEntity(label = "测试主机", host = "host", username = "root", authType = AuthType.PASSWORD,
                encPassword = byteArrayOf(3), credentialId = credentialId, keyId = keyId))
            val hosts = db.hostDao().getAll()
            val keys = db.keyDao().getAll()
            val credentials = db.credentialDao().getAll()
            val dao = db.knownHostDao()
            val verifier = AsshHostKeyVerifier(dao)
            val oldKey = rsaKey()
            val newKey = rsaKey()

            // 首次：TOFU 记录
            assertTrue(verifier.verify("host", 22, oldKey))
            val old = dao.findAll("host:22").single()
            // 同钥再连：命中
            assertTrue(verifier.verify("host", 22, oldKey))
            assertEquals(old.fingerprintSha256, dao.findAll("host:22").single().fingerprintSha256)

            // 陌生密钥：仍然拒绝，且不改动已有记录
            val error = runCatching { verifier.verify("host", 22, newKey) }.exceptionOrNull()
            assertTrue(error is HostKeyChangedException)
            assertEquals(old.fingerprintSha256, dao.findAll("host:22").single().fingerprintSha256)

            // 另一台主机独立记录
            verifier.verify("other", 2222, oldKey)
            assertEquals(1, dao.findAll("other:2222").size)

            // 全清
            dao.clear()
            assertTrue(dao.findAll("host:22").isEmpty())
            assertTrue(dao.findAll("other:2222").isEmpty())

            // 清空后重新记录新密钥
            assertTrue(verifier.verify("host", 22, newKey))
            assertNotEquals(old.fingerprintSha256, dao.findAll("host:22").single().fingerprintSha256)

            // 其他表不受影响
            assertEquals(hosts, db.hostDao().getAll())
            assertEquals(keys, db.keyDao().getAll())
            assertEquals(credentials, db.credentialDao().getAll())
            assertNotNull(db.hostDao().findById(hostId))
        } finally { db.close() }
    }

    @Test
    fun `同一主机可记录多把密钥且任一把命中即通过`() = runBlocking {
        val db = db()
        try {
            val dao = db.knownHostDao()
            val verifier = AsshHostKeyVerifier(dao)
            // 模拟负载均衡：同一个 host:22 后面三台机器
            val k1 = rsaKey(); val k2 = rsaKey(); val k3 = rsaKey()

            assertTrue(verifier.verify("lb.example.com", 22, k1))
            // 第二台是陌生密钥 → 弹窗；用户确认后追加信任
            assertTrue(runCatching { verifier.verify("lb.example.com", 22, k2) }.exceptionOrNull() is HostKeyChangedException)
            val fp2 = net.schmizz.sshj.common.SecurityUtils.getFingerprint(k2)
            dao.insertBlocking(
                KnownHostEntity(
                    id = KnownHostEntity.makeId("lb.example.com:22", fp2),
                    hostPort = "lb.example.com:22",
                    keyType = "rsa",
                    fingerprintSha256 = fp2,
                    publicKeyBlob = k2.encoded!!
                )
            )

            // 现在 k1、k2 都应通过；k3 仍陌生
            assertTrue(verifier.verify("lb.example.com", 22, k1))
            assertTrue(verifier.verify("lb.example.com", 22, k2))
            assertTrue(runCatching { verifier.verify("lb.example.com", 22, k3) }.exceptionOrNull() is HostKeyChangedException)

            // 两条记录并存，说明没有互相顶掉
            assertEquals(2, dao.findAll("lb.example.com:22").size)
        } finally { db.close() }
    }

    @Test
    fun `原因链提取识别包装异常且不误报网络密码或循环原因`() {
        val changed = HostKeyChangedException("host:22", "旧", "新", "rsa", byteArrayOf())
        assertSame(changed, IOException("包装", IllegalStateException("包装", changed)).hostKeyChange())
        assertNull(IOException("网络断线").hostKeyChange())
        assertNull(IllegalStateException("密码未提供").hostKeyChange())
        val first = IOException("第一层")
        val second = IOException("第二层", first)
        first.initCause(second)
        assertNull(first.hostKeyChange())
    }
}
