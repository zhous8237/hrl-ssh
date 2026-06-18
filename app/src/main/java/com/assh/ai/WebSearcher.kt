package com.assh.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * 给 Agent 用的网页搜索（DuckDuckGo HTML 端点，无需 API Key）。
 * 返回前若干条「标题 / 链接 / 摘要」文本，模型可据此再用 [WebFetcher] 读取具体页面。
 *
 * 依赖 DDG 的 HTML 结构，属尽力而为：抓取失败 / 结构变动时返回可读错误，模型可降级。
 */
class WebSearcher {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, maxResults: Int = 6): String = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext "搜索词为空"
        val form = FormBody.Builder().add("q", q).build()
        val req = Request.Builder()
            .url("https://html.duckduckgo.com/html/")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .post(form)
            .build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@use "搜索失败：HTTP ${resp.code}"
                parseResults(html, maxResults)
            }
        }.getOrElse { e -> "搜索失败：${e.message ?: e.javaClass.simpleName}" }
    }

    private fun parseResults(html: String, max: Int): String {
        val linkRe = Regex("""<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snipRe = Regex("""<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val links = linkRe.findAll(html).map { it.groupValues[1] to stripTags(it.groupValues[2]) }.toList()
        val snippets = snipRe.findAll(html).map { stripTags(it.groupValues[1]) }.toList()
        if (links.isEmpty()) return "未找到结果"
        val sb = StringBuilder()
        for (i in links.indices.take(max)) {
            val (href, title) = links[i]
            val url = realUrl(href)
            val snippet = snippets.getOrNull(i).orEmpty()
            sb.append(i + 1).append(". ").append(title).append('\n')
            sb.append("   ").append(url).append('\n')
            if (snippet.isNotBlank()) sb.append("   ").append(snippet).append('\n')
        }
        return sb.toString().trim()
    }

    /** DDG 的 href 形如 //duckduckgo.com/l/?uddg=<编码真实URL>&rut=... */
    private fun realUrl(href: String): String {
        val m = Regex("""uddg=([^&]+)""").find(href) ?: return href
        return runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrDefault(href)
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#x27;", "'").replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ").trim()
}
