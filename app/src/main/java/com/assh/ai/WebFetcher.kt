package com.assh.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 给 Agent 用的网页抓取器：拉取一个 URL 的文本内容（GitHub README、文档、网页等），
 * 去掉 script/style 与 HTML 标签后截断返回，供模型了解项目信息、依赖、部署要求。
 *
 * 走 App 所在网络（手机），与 SSH 目标服务器无关。
 */
class WebFetcher {

    /**
     * 拦内网/保留地址的 DNS：解析后任一地址命中私有/环回/链路本地等即拒绝。
     * 在 DNS 阶段拦截、建连前生效；okhttp 跟随重定向时会重新走本 DNS，故重定向到内网也挡得住。
     * 防 AI 被 prompt injection 诱导借手机网络探测/访问内网或云元数据（169.254.169.254）。
     */
    private val safeDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addrs = Dns.SYSTEM.lookup(hostname)
            if (addrs.any { it.isBlockedTarget() }) {
                throw UnknownHostException("拒绝访问内网/保留地址：$hostname")
            }
            return addrs
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .dns(safeDns)
        .build()

    suspend fun fetch(url: String, maxChars: Int = 12_000): String = withContext(Dispatchers.IO) {
        val u = url.trim()
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) {
            return@withContext "无效 URL（需以 http:// 或 https:// 开头）：$u"
        }
        val req = Request.Builder()
            .url(u)
            .header("User-Agent", "Mozilla/5.0 (Android) assh-agent")
            .header("Accept", "text/plain, text/html, application/json, */*")
            .get()
            .build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@use "HTTP ${resp.code}：${body.take(500)}"
                }
                val clean = stripHtml(body)
                if (clean.length > maxChars) clean.take(maxChars) + "\n…（内容已截断）" else clean
            }
        }.getOrElse { e ->
            "抓取失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun stripHtml(s: String): String {
        // 纯文本（README/JSON）直接返回；HTML 去脚本样式标签后压缩空行
        val looksHtml = s.contains("<html", true) || s.contains("<body", true) || s.contains("<div", true)
        if (!looksHtml) return s.trim()
        return s
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("(?s)<[^>]+>"), " ")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /** 内网 / 保留地址判定：环回、私有段、链路本地（含云元数据 169.254.x）、任意地址、组播、CGNAT */
    private fun InetAddress.isBlockedTarget(): Boolean {
        if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress ||
            isSiteLocalAddress || isMulticastAddress) return true
        val raw = address
        if (raw.size == 4) {
            val b0 = raw[0].toInt() and 0xff
            val b1 = raw[1].toInt() and 0xff
            if (b0 == 0) return true                       // 0.0.0.0/8
            if (b0 == 100 && b1 in 64..127) return true    // CGNAT 100.64.0.0/10
        }
        return false
    }
}
