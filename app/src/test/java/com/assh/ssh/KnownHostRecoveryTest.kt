package com.assh.ssh

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.assh.data.db.AsshDatabase
import com.assh.data.db.entity.AuthType
import com.assh.data.db.entity.CredentialEntity
import com.assh.data.db.entity.HostEntity
import com.assh.data.db.entity.KeyEntity
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
    @Test
    fun `真实校验器首次记录匹配通过变更失败清空后记录新指纹且保留其他表`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AsshDatabase::class.java)
            .allowMainThreadQueries().build()
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
            val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            val oldKey = generator.generateKeyPair().public
            val newKey = generator.generateKeyPair().public
            assertTrue(verifier.verify("host", 22, oldKey))
            val old = dao.find("host:22")!!
            assertTrue(verifier.verify("host", 22, oldKey))
            assertEquals(old.fingerprintSha256, dao.find("host:22")!!.fingerprintSha256)
            val error = runCatching { verifier.verify("host", 22, newKey) }.exceptionOrNull()
            assertTrue(error is HostKeyChangedException)
            assertEquals(old.fingerprintSha256, dao.find("host:22")!!.fingerprintSha256)
            verifier.verify("other", 2222, oldKey)
            dao.clear()
            assertNull(dao.find("host:22"))
            assertNull(dao.find("other:2222"))
            dao.clear()
            assertTrue(verifier.verify("host", 22, newKey))
            assertNotEquals(old.fingerprintSha256, dao.find("host:22")!!.fingerprintSha256)
            assertEquals(hosts, db.hostDao().getAll())
            assertEquals(keys, db.keyDao().getAll())
            assertEquals(credentials, db.credentialDao().getAll())
            assertNotNull(db.hostDao().findById(hostId))
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
