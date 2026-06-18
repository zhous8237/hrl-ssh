package com.assh.sync

import com.assh.sync.vault.VaultCodec
import com.assh.sync.vault.VaultFormatException
import com.assh.sync.vault.WrongPassphraseException
import com.assh.sync.webdav.RemoteStore
import com.assh.sync.webdav.RemoteStoreFactory
import com.assh.sync.webdav.WebDavException
import com.assh.sync.webdav.WebDavStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 同步编排（功能 7，开发文档 v2 §5.1）。三种模式对应 UI 三按钮：
 * - [SyncMode.PUSH] 同步到云端：本地为准，覆盖云端。
 * - [SyncMode.PULL] 同步到本地：云端为准，镜像覆盖本地。
 * - [SyncMode.MERGE] 智能合并：双向 LWW 合并后写回两端。
 *
 * 并发感知一律基于 vault 内部的 [com.assh.sync.vault.VaultDto.vaultVersion]，
 * **不再依赖 WebDAV 的 If-Match/ETag 乐观锁**：实测多数服务端（如 InfiniCloud）
 * PUT 响应回的 ETag 与 GET 不一致、或返回弱 ETag（`W/"…"`），条件 PUT 在强比较下
 * 恒不匹配 → 每次同步都误报 412「云端已被其它设备更新」。改用版本号后，上传一律
 * 无条件 PUT，版本号只用于保证单调递增 + 合并时取较新者。
 */
class SyncEngine(
    private val prefs: SyncPreferences,
    private val repo: SyncRepository,
    private val storeFactory: RemoteStoreFactory = WebDavStoreFactory()
) {
    companion object {
        private const val VAULT_NAME = "assh-vault.json.enc"
    }

    /** 连通性测试（不涉及同步口令） */
    suspend fun testConnection(): SyncResult = withContext(Dispatchers.IO) {
        try {
            val cfg = prefs.resolveConfig()
            if (cfg.baseUrl.isBlank() || cfg.username.isBlank() || cfg.password.isBlank()) {
                return@withContext SyncResult.Err("请先填写服务器地址、账号与密码")
            }
            storeFactory.create(cfg).testConnection()
            SyncResult.Ok(SyncMode.PUSH, SyncStats(), "连接成功")
        } catch (e: Exception) {
            SyncResult.Err(readableError(e))
        }
    }

    suspend fun sync(mode: SyncMode, passphrase: CharArray): SyncResult = withContext(Dispatchers.IO) {
        try {
            val cfg = prefs.resolveConfig()
            if (!cfg.isUsable) return@withContext SyncResult.Err("请先填写并保存 WebDAV 端点与密码")
            if (passphrase.isEmpty()) return@withContext SyncResult.Err("请输入同步口令")
            val dav = storeFactory.create(cfg)
            val deviceId = prefs.deviceId()
            when (mode) {
                SyncMode.PUSH -> doPush(dav, deviceId, passphrase)
                SyncMode.PULL -> doPull(dav, passphrase)
                SyncMode.MERGE -> doMerge(dav, deviceId, passphrase)
            }
        } catch (e: Exception) {
            SyncResult.Err(readableError(e))
        }
    }

    private suspend fun doPush(dav: RemoteStore, deviceId: String, passphrase: CharArray): SyncResult {
        // 探测云端当前版本，使新版本号在多设备间单调递增（云端解不开则按 0 处理）。
        val remote = dav.download(VAULT_NAME)
        val remoteVersion = remote?.let {
            runCatching { VaultCodec.decode(it.first, passphrase).vaultVersion }.getOrDefault(0L)
        } ?: 0L
        val newVersion = maxOf(prefs.vaultVersion(), remoteVersion) + 1
        val dto = repo.exportToDto(newVersion, deviceId)
        val bytes = VaultCodec.encode(dto, passphrase)
        // PUSH 语义即「本地为准、覆盖云端」，无条件上传
        val etag = dav.upload(VAULT_NAME, bytes)
        prefs.saveSyncMeta(newVersion, etag)
        return SyncResult.Ok(SyncMode.PUSH, repo.localStats(), "已上传本地配置到云端")
    }

    private suspend fun doPull(dav: RemoteStore, passphrase: CharArray): SyncResult {
        val remote = dav.download(VAULT_NAME)
            ?: return SyncResult.Err("云端暂无备份，请先「同步到云端」")
        val dto = VaultCodec.decode(remote.first, passphrase)
        val stats = repo.applyMirror(dto)
        prefs.saveSyncMeta(dto.vaultVersion, remote.second)
        return SyncResult.Ok(SyncMode.PULL, stats, "已从云端恢复到本地")
    }

    private suspend fun doMerge(dav: RemoteStore, deviceId: String, passphrase: CharArray): SyncResult {
        val remote = dav.download(VAULT_NAME)
            ?: return doPush(dav, deviceId, passphrase) // 云端为空：首次合并退化为推送
        val remoteDto = VaultCodec.decode(remote.first, passphrase)
        val localDto = repo.exportToDto(0, deviceId)
        val newVersion = maxOf(remoteDto.vaultVersion, prefs.vaultVersion()) + 1
        val merged = repo.merge(localDto, remoteDto, newVersion, deviceId)
        val stats = repo.applyMirror(merged)
        val bytes = VaultCodec.encode(merged, passphrase)
        // 合并结果已纳入云端最新状态，无条件写回（同 doPush）
        val etag = dav.upload(VAULT_NAME, bytes)
        prefs.saveSyncMeta(newVersion, etag)
        return SyncResult.Ok(SyncMode.MERGE, stats, "合并完成")
    }

    private fun readableError(e: Throwable): String = when (e) {
        is WrongPassphraseException -> e.message ?: "同步口令错误"
        is VaultFormatException -> e.message ?: "同步包格式错误"
        is WebDavException -> e.message ?: "WebDAV 错误"
        else -> e.message ?: "同步失败：${e.javaClass.simpleName}"
    }
}
