package com.assh.sync

import com.assh.sync.vault.VaultCodec
import com.assh.sync.vault.VaultDto
import com.assh.sync.webdav.FakeRemoteStore
import com.assh.sync.webdav.RemoteStoreFactory
import com.assh.sync.webdav.WebDavConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SyncEngine] PUSH/PULL/MERGE 编排测试（C4）。
 * 用 FakeRemoteStore 替掉真实 WebDAV，MockK 替掉 prefs/repo——此前 SyncEngine 直接
 * new WebDavClient，整条编排无法离开网络测试。VaultCodec 走真实 encode/decode。
 */
class SyncEngineTest {

    private val passphrase = "p@ss".toCharArray()
    private val cfg = WebDavConfig(baseUrl = "https://dav/x/", username = "u", password = "pw")
    private val vaultName = "assh-vault.json.enc"

    private fun dto(version: Long) = VaultDto(vaultVersion = version, exportedAt = 0, deviceId = "dev")

    private fun engineWith(store: FakeRemoteStore): Triple<SyncEngine, SyncPreferences, SyncRepository> {
        val prefs = mockk<SyncPreferences>(relaxed = true)
        val repo = mockk<SyncRepository>(relaxed = true)
        every { runBlocking { prefs.resolveConfig() } } returns cfg
        every { runBlocking { prefs.deviceId() } } returns "dev"
        every { runBlocking { prefs.vaultVersion() } } returns 0L
        val factory = RemoteStoreFactory { store }
        return Triple(SyncEngine(prefs, repo, factory), prefs, repo)
    }

    @Test
    fun `PUSH exports and uploads`() = runBlocking {
        val store = FakeRemoteStore()
        val (engine, _, repo) = engineWith(store)
        coEvery { repo.exportToDto(any(), any()) } returns dto(1)
        coEvery { repo.localStats() } returns SyncStats(hosts = 2)

        val result = engine.sync(SyncMode.PUSH, passphrase.copyOf())

        assertTrue(result is SyncResult.Ok)
        assertEquals("应上传一次", 1, store.uploadCalls)
        coVerify { repo.exportToDto(any(), any()) }
    }

    @Test
    fun `PULL downloads and mirrors into local`() = runBlocking {
        val store = FakeRemoteStore()
        store.seed(vaultName, VaultCodec.encode(dto(5), passphrase.copyOf()))
        val (engine, _, repo) = engineWith(store)
        coEvery { repo.applyMirror(any()) } returns SyncStats(hosts = 3)

        val result = engine.sync(SyncMode.PULL, passphrase.copyOf())

        assertTrue(result is SyncResult.Ok)
        coVerify { repo.applyMirror(any()) }
        assertEquals("PULL 不应上传", 0, store.uploadCalls)
    }

    @Test
    fun `PULL with empty cloud returns error`() = runBlocking {
        val store = FakeRemoteStore()  // 远端空
        val (engine, _, _) = engineWith(store)

        val result = engine.sync(SyncMode.PULL, passphrase.copyOf())

        assertTrue("云端无备份应报错", result is SyncResult.Err)
    }

    @Test
    fun `MERGE on empty cloud degrades to push`() = runBlocking {
        val store = FakeRemoteStore()  // 远端空 → 退化为 push
        val (engine, _, repo) = engineWith(store)
        coEvery { repo.exportToDto(any(), any()) } returns dto(1)
        coEvery { repo.localStats() } returns SyncStats()

        val result = engine.sync(SyncMode.MERGE, passphrase.copyOf())

        assertTrue(result is SyncResult.Ok)
        assertEquals("首次合并退化为推送，应上传一次", 1, store.uploadCalls)
    }

    @Test
    fun `MERGE downloads merges and writes back to both`() = runBlocking {
        val store = FakeRemoteStore()
        store.seed(vaultName, VaultCodec.encode(dto(4), passphrase.copyOf()))
        val (engine, _, repo) = engineWith(store)
        coEvery { repo.exportToDto(any(), any()) } returns dto(0)
        coEvery { repo.merge(any(), any(), any(), any()) } returns dto(5)
        coEvery { repo.applyMirror(any()) } returns SyncStats(hosts = 1)

        val result = engine.sync(SyncMode.MERGE, passphrase.copyOf())

        assertTrue(result is SyncResult.Ok)
        coVerify { repo.merge(any(), any(), any(), any()) }
        coVerify { repo.applyMirror(any()) }
        assertEquals("合并结果应写回云端", 1, store.uploadCalls)
    }

    @Test
    fun `wrong passphrase on PULL surfaces error`() = runBlocking {
        val store = FakeRemoteStore()
        store.seed(vaultName, VaultCodec.encode(dto(5), "right".toCharArray()))
        val (engine, _, _) = engineWith(store)

        val result = engine.sync(SyncMode.PULL, "wrong".toCharArray())

        assertTrue("错误口令应报错而非崩溃", result is SyncResult.Err)
    }

    @Test
    fun `empty passphrase rejected`() = runBlocking {
        val store = FakeRemoteStore()
        val (engine, _, _) = engineWith(store)

        val result = engine.sync(SyncMode.PUSH, CharArray(0))

        assertTrue(result is SyncResult.Err)
        assertEquals("空口令不应触达网络", 0, store.uploadCalls)
    }
}
