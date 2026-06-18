package com.assh.sync

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.assh.data.crypto.CryptoManager
import com.assh.sync.webdav.WebDavConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import android.util.Base64

private val Context.syncDataStore by preferencesDataStore(name = "sync_settings")

/**
 * WebDAV 同步配置持久化（功能 7）。
 *
 * 端点凭据（WebDAV 密码、可选的同步口令）属敏感信息，用 v1 [CryptoManager]
 * 设备 Keystore 加密后以 Base64 文本存 DataStore —— 它们是本设备配置，不参与跨设备同步包。
 * 同步口令是否记住由用户决定（开启自动填充则缓存，否则每次手动输入）。
 *
 * 同步包直接放在 WebDAV 地址根下，不再使用远端子目录。
 */
class SyncPreferences(private val context: Context) {

    private val kBaseUrl = stringPreferencesKey("webdav_base_url")
    private val kUsername = stringPreferencesKey("webdav_username")
    private val kEncPassword = stringPreferencesKey("webdav_enc_password")     // Base64(Keystore 密文)
    private val kRememberPass = booleanPreferencesKey("remember_passphrase")
    private val kEncPassphrase = stringPreferencesKey("enc_passphrase")        // Base64(Keystore 密文)
    private val kLastSyncAt = longPreferencesKey("last_sync_at")
    private val kLastEtag = stringPreferencesKey("last_etag")
    private val kVaultVersion = longPreferencesKey("vault_version")
    private val kDeviceId = stringPreferencesKey("device_id")

    val settings: Flow<SyncSettings> = context.syncDataStore.data.map { p ->
        SyncSettings(
            baseUrl = p[kBaseUrl].orEmpty(),
            username = p[kUsername].orEmpty(),
            hasPassword = !p[kEncPassword].isNullOrBlank(),
            rememberPassphrase = p[kRememberPass] ?: false,
            hasSavedPassphrase = !p[kEncPassphrase].isNullOrBlank(),
            lastSyncAt = p[kLastSyncAt]
        )
    }

    /** 解析出可用于 WebDavClient 的明文配置；密码缺失则返回的 password 为空 */
    suspend fun resolveConfig(): WebDavConfig {
        val p = context.syncDataStore.data.first()
        val pwd = p[kEncPassword]?.let { decB64(it) }.orEmpty()
        return WebDavConfig(
            baseUrl = p[kBaseUrl].orEmpty(),
            username = p[kUsername].orEmpty(),
            password = pwd
        )
    }

    /** 保存 WebDAV 端点配置；password 传 null = 不改密码，"" = 清空 */
    suspend fun saveWebDav(baseUrl: String, username: String, password: String?) {
        context.syncDataStore.edit { p ->
            p[kBaseUrl] = baseUrl.trim()
            p[kUsername] = username.trim()
            when {
                password == null -> Unit
                password.isEmpty() -> p.remove(kEncPassword)
                else -> p[kEncPassword] = encB64(password)
            }
        }
    }

    /** 取已记住的同步口令明文；未记住返回 null */
    suspend fun resolvePassphrase(): String? {
        val p = context.syncDataStore.data.first()
        if (p[kRememberPass] != true) return null
        return p[kEncPassphrase]?.let { decB64(it) }
    }

    /** 记住/忘记同步口令 */
    suspend fun setRememberPassphrase(remember: Boolean, passphrase: String?) {
        context.syncDataStore.edit { p ->
            p[kRememberPass] = remember
            if (remember && !passphrase.isNullOrEmpty()) {
                p[kEncPassphrase] = encB64(passphrase)
            } else if (!remember) {
                p.remove(kEncPassphrase)
            }
        }
    }

    // —— 同步元数据（乐观锁 / 并发检测）——

    suspend fun lastEtag(): String? = context.syncDataStore.data.first()[kLastEtag]

    suspend fun vaultVersion(): Long = context.syncDataStore.data.first()[kVaultVersion] ?: 0L

    /** 本设备稳定 id（首次访问时生成并持久化） */
    suspend fun deviceId(): String {
        val existing = context.syncDataStore.data.first()[kDeviceId]
        if (!existing.isNullOrBlank()) return existing
        val id = java.util.UUID.randomUUID().toString()
        context.syncDataStore.edit { it[kDeviceId] = id }
        return id
    }

    suspend fun saveSyncMeta(vaultVersion: Long, etag: String?) {
        context.syncDataStore.edit { p ->
            p[kVaultVersion] = vaultVersion
            p[kLastSyncAt] = System.currentTimeMillis()
            if (etag != null) p[kLastEtag] = etag else p.remove(kLastEtag)
        }
    }

    private fun encB64(plain: String): String =
        Base64.encodeToString(CryptoManager.encryptString(plain), Base64.NO_WRAP)

    private fun decB64(b64: String): String =
        CryptoManager.decryptString(Base64.decode(b64, Base64.NO_WRAP))
}

/** UI 观察用的同步设置快照（不含明文密码/口令） */
data class SyncSettings(
    val baseUrl: String = "",
    val username: String = "",
    val hasPassword: Boolean = false,
    val rememberPassphrase: Boolean = false,
    val hasSavedPassphrase: Boolean = false,
    val lastSyncAt: Long? = null
)
