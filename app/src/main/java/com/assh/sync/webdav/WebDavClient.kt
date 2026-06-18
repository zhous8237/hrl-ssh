package com.assh.sync.webdav

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** WebDAV 请求失败（鉴权 / 路径 / 服务器错误等），message 含可读原因 */
class WebDavException(val statusCode: Int, msg: String) : Exception(msg)

/**
 * WebDAV 客户端（功能 7，开发文档 v2 §3）。自封装 OkHttp，只用到少数动词：
 * PROPFIND（探测）、GET（下载）、PUT（上传）、DELETE（重置）。
 * 实现 [RemoteStore] seam（C4），生产路径由 [WebDavStoreFactory] 构造。
 *
 * 同步包直接放在 [WebDavConfig.baseUrl] 指向的目录下，不再创建子目录、也不再发
 * MKCOL —— 部分反向代理/网关不支持 MKCOL 会返回 502，直接 PUT 标准方法可规避。
 * 所有方法在 IO 线程执行（OkHttp 同步 execute），调用方需在协程 Dispatchers.IO 中调用。
 */
class WebDavClient(private val cfg: WebDavConfig) : RemoteStore {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // WebDAV 客户端须禁用自动重定向：OkHttp 跟随 30x 时会把 PROPFIND/PUT 降级成
        // GET、甚至丢掉 /dav/ 路径（实测 http→https、漏尾斜杠都会触发），导致打到
        // 错误路径返回 404/405。禁用后由本类显式控制每个动词的目标 URL。
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val dirUrl: String
        get() = cfg.baseUrl.trim().trimEnd('/') + "/"

    private fun urlOf(name: String) = dirUrl + name

    private fun auth() = Credentials.basic(cfg.username, cfg.password)

    /**
     * 连通性测试，分两步给出可区分的失败原因：
     * 1) OPTIONS 探测：响应须带 `DAV` 能力头或 `Allow` 含 PROPFIND，才证明目标
     *    确实是 WebDAV 端点。若 2xx 却无 WebDAV 能力，说明请求被网络中间节点/
     *    代理/DNS 导向了普通 Web 服务器（实测 InfiniCloud 链路异常时 Allow 仅
     *    GET,POST,OPTIONS,HEAD 且 GET 返回 404）——此时直指链路问题，而非地址错。
     * 2) PROPFIND 校验认证与路径：207/2xx 通过；401/403 说明地址可达、仅认证失败；
     *    部分网关对 PROPFIND 返回 405，但 OPTIONS 已确认是 WebDAV，故视为可达。
     * 失败抛 [WebDavException]，message 区分 401/404/5xx/SSL/非 WebDAV 等。
     */
    override fun testConnection() {
        runCatching {            // 1) OPTIONS：确认这是 WebDAV 端点，而非被链路导偏的普通 Web 节点
            val optReq = Request.Builder()
                .url(dirUrl)
                .method("OPTIONS", null)
                .header("Authorization", auth())
                .build()
            http.newCall(optReq).execute().use { resp ->
                val allow = resp.header("Allow").orEmpty().uppercase()
                val isWebDav = !resp.header("DAV").isNullOrBlank() ||
                    allow.contains("PROPFIND") || allow.contains("PROPPATCH")
                when {
                    // 认证墙在前（401/403）：留待 PROPFIND 给出准确的认证错误
                    resp.code == 401 || resp.code == 403 -> {}
                    resp.isSuccessful && !isWebDav -> throw WebDavException(
                        resp.code,
                        "该地址不是 WebDAV 服务（响应缺少 DAV 能力头）。多为网络/代理/DNS 把请求导向了其它服务器，请检查代理或换用能访问 InfiniCloud 的网络"
                    )
                    !resp.isSuccessful && resp.code != 405 ->
                        throw WebDavException(resp.code, httpMessage(resp.code, "连接失败"))
                }
            }
            // 2) PROPFIND：校验认证与路径。405 时 OPTIONS 已确认是 WebDAV，视为可达
            val pfReq = Request.Builder()
                .url(dirUrl)
                .method("PROPFIND", null)
                .header("Authorization", auth())
                .header("Depth", "0")
                .build()
            http.newCall(pfReq).execute().use { resp ->
                if (resp.code != 207 && resp.code != 405 && !resp.isSuccessful) {
                    throw WebDavException(resp.code, httpMessage(resp.code, "连接失败"))
                }
            }
        }.getOrElse { e ->
            if (e is WebDavException) throw e
            throw WebDavException(-1, mapNetworkError(e))
        }
    }

    /** 下载同步包；返回 (字节, ETag)，文件不存在返回 null */
    override fun download(name: String): Pair<ByteArray, String?>? {
        val req = Request.Builder()
            .url(urlOf(name))
            .header("Authorization", auth())
            .get()
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 404 -> null
                    resp.isSuccessful -> resp.body!!.bytes() to resp.header("ETag")
                    else -> throw WebDavException(resp.code, httpMessage(resp.code, "下载失败"))
                }
            }
        }.getOrElse { e ->
            if (e is WebDavException) throw e
            throw WebDavException(-1, mapNetworkError(e))
        }
    }

    /**
     * 无条件上传同步包并返回新 ETag。并发控制改用 vault 内部的 vaultVersion，
     * 不再用 If-Match 乐观锁（实测多数服务端 ETag 弱比较恒不匹配，会每次误报 412）。
     */
    override fun upload(name: String, data: ByteArray): String? {
        val builder = Request.Builder()
            .url(urlOf(name))
            .header("Authorization", auth())
            .put(data.toRequestBody("application/octet-stream".toMediaType()))
        return runCatching {
            http.newCall(builder.build()).execute().use { resp ->
                when {
                    resp.isSuccessful -> resp.header("ETag")
                    else -> throw WebDavException(resp.code, httpMessage(resp.code, "上传失败"))
                }
            }
        }.getOrElse { e ->
            if (e is WebDavException) throw e
            throw WebDavException(-1, mapNetworkError(e))
        }
    }

    /** 删除远端同步包（重置同步用） */
    override fun delete(name: String) {
        val req = Request.Builder()
            .url(urlOf(name))
            .header("Authorization", auth())
            .delete()
            .build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 404) {
                    throw WebDavException(resp.code, httpMessage(resp.code, "删除失败"))
                }
            }
        }.getOrElse { e ->
            if (e is WebDavException) throw e
            throw WebDavException(-1, mapNetworkError(e))
        }
    }

    /** HTTP 状态码 → 可读中文（action 形如 "上传失败"/"下载失败"） */
    private fun httpMessage(code: Int, action: String): String = when {
        code == 401 -> "认证失败：账号或密码错误"
        code == 403 -> "无权限访问（HTTP 403）"
        code == 404 -> "路径不存在：请检查 WebDAV 地址（HTTP 404）"
        code == 405 -> "服务器不支持该操作（HTTP 405），请确认地址是 WebDAV 目录"
        code in 500..599 -> "服务器/网关错误（HTTP $code），请检查 WebDAV 服务或反向代理配置"
        else -> "$action：HTTP $code"
    }

    private fun mapNetworkError(e: Throwable): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when {
            msg.contains("CertPath", true) || msg.contains("SSL", true) ||
                msg.contains("trust", true) -> "SSL 证书校验失败（自签证书需在系统中信任）"
            msg.contains("Unable to resolve host", true) ||
                msg.contains("UnknownHost", true) -> "无法解析服务器地址，请检查网络与 URL"
            msg.contains("timeout", true) -> "连接超时，请检查网络"
            else -> "网络错误：$msg"
        }
    }
}
