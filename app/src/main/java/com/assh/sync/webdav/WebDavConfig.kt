package com.assh.sync.webdav

/**
 * 解析后的 WebDAV 连接配置（明文，仅在内存中使用）。
 * 持久化（含口令加密）见 com.assh.sync.SyncPreferences。
 */
data class WebDavConfig(
    val baseUrl: String,      // 如 https://dav.example.com/remote.php/dav/files/user/，同步包直接放该地址根下
    val username: String,
    val password: String
) {
    val isUsable: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    val isHttps: Boolean
        get() = baseUrl.trim().startsWith("https://", ignoreCase = true)
}
