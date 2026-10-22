package com.assh.sshconfig

/**
 * OpenSSH 客户端配置（`~/.ssh/config`、Windows 上 `%USERPROFILE%\.ssh\config`）解析器。
 *
 * 只做“批量导入主机条目”需要的那部分语义，纯 Kotlin、无 Android 依赖，便于单测：
 * - `Host` / `Match` 块分节；关键字与值可用空格或 `=` 分隔
 * - `Host a b` 多别名：每个具名别名各生成一条主机，值取该块生效后的结果
 * - 关键字大小写不敏感，**首次出现的值生效**（与 OpenSSH 一致：先匹配到的选项优先）
 * - 通配条目（`Host *`、`Host web-?`、`!bastion`）不生成主机条目（OpenSSH 中它们是
 *   “给匹配主机补默认值”的模板，本身不是可连接名字）
 * - `Include`：递归展开（绝对路径、`~` 展开、相对当前文件目录），带环检测与深度上限
 * - `%d`（本地 home）、`%h`（HostName）、`%r`（User）、`%n`（配置中的 Host 名）令牌替换
 *
 * 不在范围内（本 App 不使用，导入时记入 [ParsedHost.unsupportedOptions] 供 UI 提示）：
 * `ProxyCommand`、`ProxyJump`、`LocalForward`、`RemoteForward`、`DynamicForward`、
 * `CertificateFile`、`Match exec`、`ControlMaster` 等。
 */
object SshConfigParser {

    /** 单个别名解析结果（未落库的中间形态） */
    data class ParsedHost(
        /** 配置中的主机名（`Host` 行里的别名），也是导入后的显示名 */
        val name: String,
        /** 真实地址：`HostName`，缺省取 [name] */
        val hostName: String,
        val port: Int,
        val user: String?,
        /** `IdentityFile` 路径（已做令牌展开），可为空 */
        val identityFile: String?,
        /** 该块内出现的、本 App 不支持的选项名（去重、保持出现顺序） */
        val unsupportedOptions: List<String> = emptyList(),
        /** 来自哪个文件（诊断/展示用） */
        val sourceFile: String? = null,
        /** 该条目所在的 `Host` 行原文别名列表，仅用于展示 */
        val aliases: List<String> = emptyList()
    )

    /** 解析过程告警（不中断解析，UI 汇总展示） */
    data class Warning(val file: String?, val line: Int, val message: String)

    data class Result(
        val hosts: List<ParsedHost>,
        val warnings: List<Warning> = emptyList()
    )

    /**
     * 本解析器不实现、但常见于 SSH 配置的关键字：命中即记入 [ParsedHost.unsupportedOptions]，
     * 导入预览里明确显示“已忽略”，而不是静默丢弃 —— 用户需要知道自己写的选项没生效。
     *
     * 其中算法协商类（pubkeyacceptedkeytypes / hostkeyalgorithms / ciphers / macs / kexalgorithms /
     * pubkeyacceptedalgorithms）只影响协商阶段，本 App 用 sshj 默认算法集，
     * 只要服务器不强制证书类算法仍能正常连接；这里提示“未应用”以免误解为已生效。
     */
    private val UNSUPPORTED_KEYWORDS = setOf(
        // 隧道 / 转发 / 代理
        "proxycommand", "proxyjump", "localforward", "remoteforward",
        "dynamicforward", "sendenv", "setenv",
        // 连接复用 / 证书 / 智能卡
        "certificatefile", "pkcs11provider", "addkeystoagent",
        "canonicalizehostname", "controlmaster", "controlpath", "controlpersist",
        // 算法协商（仅影响协商，不改变本 App 的 sshj 默认算法集）
        "pubkeyacceptedkeytypes", "pubkeyacceptedalgorithms", "hostkeyalgorithms",
        "ciphers", "macs", "kexalgorithms"
    )

    private const val MAX_INCLUDE_DEPTH = 8
    private const val MATCH_SENTINEL = "\u0000match"

    private data class RawBlock(
        val patterns: List<String>,
        val options: List<Pair<String, String>>,
        val file: String?,
        val line: Int,
        val isMatch: Boolean
    )

    /**
     * 解析入口。
     *
     * @param configText 主配置文件全文
     * @param homeDir 用于 `~` 与 `%d` 展开的 home 目录（仅做语法层展开，不需要真实可访问）
     * @param inlineFiles `Include` 指向的其他配置文件内容：路径 → 全文。为空时 `Include` 只产生告警。
     * @param sourceName 主文件的展示名
     */
    fun parse(
        configText: String,
        homeDir: String? = null,
        inlineFiles: Map<String, String> = emptyMap(),
        sourceName: String? = "config"
    ): Result {
        val warnings = mutableListOf<Warning>()
        val blocks = mutableListOf<RawBlock>()
        collectBlocks(
            text = configText,
            file = sourceName,
            homeDir = homeDir,
            inlineFiles = inlineFiles,
            warnings = warnings,
            blocks = blocks,
            depth = 0,
            visited = hashSetOf()
        )

        // 逐个配置块展开成具体主机条目
        val seenNames = linkedMapOf<String, ParsedHost>()
        for (block in blocks) {
            if (block.isMatch) {
                warnings += Warning(block.file, block.line, "忽略 Match 块（本 App 不支持 Match 条件匹配）")
                continue
            }
            for (alias in block.patterns) {
                if (alias.isEmpty() || isPattern(alias)) continue
                val resolved = resolveBlock(alias, block, homeDir, warnings)
                // 同一别名在多个块中出现时，先出现的生效（OpenSSH 语义）
                if (seenNames.containsKey(resolved.name)) continue
                seenNames[resolved.name] = resolved
            }
        }
        return Result(seenNames.values.toList(), warnings)
    }

    // ===== 扫描阶段：把文本拆成 Host / Match 配置块 =====

    private fun collectBlocks(
        text: String,
        file: String?,
        homeDir: String?,
        inlineFiles: Map<String, String>,
        warnings: MutableList<Warning>,
        blocks: MutableList<RawBlock>,
        depth: Int,
        visited: MutableSet<String>
    ) {
        if (depth > MAX_INCLUDE_DEPTH) {
            warnings += Warning(file, 0, "Include 嵌套超过 $MAX_INCLUDE_DEPTH 层，已停止展开")
            return
        }

        var currentPatterns: List<String>? = null
        var currentIsMatch = false
        var currentStartLine = 0
        var currentOptions = mutableListOf<Pair<String, String>>()
        var skipNextFlush = false

        fun flush() {
            if (skipNextFlush) { skipNextFlush = false; return }
            val patterns = currentPatterns ?: return
            blocks += RawBlock(
                patterns = patterns,
                options = currentOptions.toList(),
                file = file,
                line = currentStartLine,
                isMatch = currentIsMatch
            )
            currentOptions = mutableListOf()
        }

        // 手写索引循环，避免 forEachIndexed + return 标签在嵌套 lambda 中的歧义
        val lines = text.lineSequence().toList()
        for (index in lines.indices) {
            val lineNo = index + 1
            val line = stripComment(lines[index]).trim()
            if (line.isEmpty()) continue

            val (key, value) = splitKeyword(line)
            if (key == null) {
                warnings += Warning(file, lineNo, "无法识别的行：$line")
                continue
            }

            when (key.lowercase()) {
                "host" -> {
                    flush()
                    currentIsMatch = false
                    currentStartLine = lineNo
                    currentPatterns = value.split(Regex("\\s+")).filter { it.isNotBlank() }
                }

                "match" -> {
                    flush()
                    currentIsMatch = true
                    currentStartLine = lineNo
                    // Match 块不参与主机生成，但吞掉其内部选项，避免污染下一个 Host 块
                    currentPatterns = listOf(MATCH_SENTINEL)
                }

                "include" -> {
                    val target = value.trim()
                    if (target.isEmpty()) {
                        warnings += Warning(file, lineNo, "Include 缺少路径")
                    } else {
                        val expanded = expandTokens(target, homeDir = homeDir)
                        val inlined = findInline(inlineFiles, expanded) ?: findInline(inlineFiles, target)
                        if (inlined == null) {
                            warnings += Warning(
                                file, lineNo,
                                "Include $target：该文件内容未提供，已跳过（本次导入只读一个配置文件）"
                            )
                        } else if (visited.add(inlined.key)) {
                            // Include 语义上是“就地插入”。当前块要把已收集的选项保住：
                            // 先落盘当前块，子文件展开后重新开一个同名块继续接收后续选项，
                            // 这样 `Host a / Include x / User u` 中 User 不会被丢掉。
                            val savedPatterns = currentPatterns
                            val savedIsMatch = currentIsMatch
                            val savedLine = currentStartLine
                            flush()
                            collectBlocks(
                                text = inlined.value,
                                file = inlined.key,
                                homeDir = homeDir,
                                inlineFiles = inlineFiles,
                                warnings = warnings,
                                blocks = blocks,
                                depth = depth + 1,
                                visited = visited
                            )
                            // 恢复被 Include 打断的块（其后续选项仍属于原 Host）
                            currentPatterns = savedPatterns
                            currentIsMatch = savedIsMatch
                            currentStartLine = savedLine
                        }
                    }
                }

                else -> {
                    if (currentPatterns == null) {
                        // OpenSSH 允许块外全局选项，但只对“后续所有 Host”生效；
                        // 为免把默认值误当成某个主机的配置，这里丢弃并告警。
                        warnings += Warning(file, lineNo, "忽略 Host 块外的全局选项：$key $value")
                    } else {
                        currentOptions += key to value
                    }
                }
            }
        }
        flush()
    }

    // ===== 解析阶段：把一个块展开成具体主机 =====

    private fun resolveBlock(
        alias: String,
        block: RawBlock,
        homeDir: String?,
        warnings: MutableList<Warning>
    ): ParsedHost {
        // OpenSSH：块内同名选项先出现者生效
        val opts = LinkedHashMap<String, String>()
        val unsupported = LinkedHashSet<String>()
        for ((k, v) in block.options) {
            val key = k.lowercase()
            if (key in UNSUPPORTED_KEYWORDS) {
                unsupported += key
                continue
            }
            if (key !in opts) opts[key] = v
        }

        val rawHostName = opts["hostname"]
        val rawUser = opts["user"]
        val rawPort = opts["port"]
        val rawIdentity = opts["identityfile"]

        // 两遍替换：%h 依赖 HostName 先确定
        val hostName = expandTokens(
            value = rawHostName ?: alias,
            homeDir = homeDir,
            hostName = rawHostName ?: alias,
            user = rawUser,
            configuredName = alias
        ).trim().ifEmpty { alias }

        val user = rawUser?.let {
            expandTokens(it, homeDir, hostName, null, configuredName = alias).trim().ifEmpty { null }
        }

        val portNum = rawPort?.trim()?.toIntOrNull()
        val port = portNum?.takeIf { it in 1..65535 } ?: 22
        if (rawPort != null && portNum == null) {
            warnings += Warning(block.file, block.line, "Port 非法（$rawPort），已按 22 处理")
        }

        val identity = rawIdentity?.let {
            expandTokens(it, homeDir, hostName, user, port, alias).trim()
                .let { p -> expandTilde(p, homeDir) }
                .ifEmpty { null }
        }

        return ParsedHost(
            name = alias,
            hostName = hostName,
            port = port,
            user = user,
            identityFile = identity,
            unsupportedOptions = unsupported.toList(),
            sourceFile = block.file,
            aliases = block.patterns.filter { p -> p != MATCH_SENTINEL }
        )
    }

    // ===== 工具 =====

    /** 去掉 `#` 注释（尊重简单引号；只有行首或空白后的 `#` 算注释，与 OpenSSH 一致） */
    private fun stripComment(line: String): String {
        var inSingle = false
        var inDouble = false
        for (i in line.indices) {
            when (line[i]) {
                '\'' -> if (!inDouble) inSingle = !inSingle
                '"' -> if (!inSingle) inDouble = !inDouble
                '#' -> if (!inSingle && !inDouble && (i == 0 || line[i - 1].isWhitespace())) {
                    return line.substring(0, i)
                }
            }
        }
        return line
    }

    /** 拆 `Keyword value` / `Keyword=value` */
    private fun splitKeyword(line: String): Pair<String?, String> {
        var i = 0
        while (i < line.length && !line[i].isWhitespace() && line[i] != '=') i++
        if (i == 0) return null to ""
        val key = line.substring(0, i)
        var j = i
        while (j < line.length && (line[j].isWhitespace() || line[j] == '=')) j++
        return key to line.substring(j).trim()
    }

    private fun normalizePath(p: String): String = p.replace('\\', '/').trimEnd('/')

    /**
     * 在“路径 → 内容”表里查 Include 目标：先精确匹配，再按路径尾段匹配
     * （配置里写 `~/.ssh/config.d/extra`、用户给的是 `/home/u/.ssh/config.d/extra`）。
     * 不做模糊 endswith——那会把短路径误配到别的文件。
     */
    private fun findInline(files: Map<String, String>, target: String): Map.Entry<String, String>? {
        if (target.isBlank()) return null
        files.entries.firstOrNull { it.key.equals(target, ignoreCase = true) }?.let { return it }
        val a = normalizePath(target)
        files.entries.firstOrNull { normalizePath(it.key) == a }?.let { return it }
        val tail = a.substringAfterLast('/')
        if (tail.length <= 3) return null
        return files.entries.firstOrNull { normalizePath(it.key).substringAfterLast('/') == tail }
    }

    /**
     * 令牌替换：`%%`→`%`、`%d`→home、`%h`→HostName、`%r`→User、`%p`→端口、
     * `%n`→配置中的 Host 名。未知令牌原样保留。
     */
    internal fun expandTokens(
        value: String,
        homeDir: String?,
        hostName: String? = null,
        user: String? = null,
        port: Int? = null,
        configuredName: String? = null
    ): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '%' -> sb.append('%')
                    'd' -> sb.append(homeDir ?: "~")
                    'h' -> if (hostName != null) sb.append(hostName) else sb.append("%h")
                    'r' -> if (user != null) sb.append(user) else sb.append("%r")
                    'p' -> if (port != null) sb.append(port) else sb.append("%p")
                    'n' -> if (configuredName != null) sb.append(configuredName) else sb.append("%n")
                    else -> sb.append(c).append(value[i + 1])
                }
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** 是否为通配/排除模式（含 `*`、`?` 或以 `!` 开头） */
    private fun isPattern(name: String): Boolean =
        name.contains('*') || name.contains('?') || name.startsWith("!")

    /**
     * 通配匹配（`*`、`?`，大小写不敏感）。用于把用户输入的主机名匹配到某个 `Host` 模式。
     */
    fun wildcardMatches(pattern: String, name: String): Boolean {
        if (pattern.isEmpty()) return false
        var p = 0
        var n = 0
        var starP = -1
        var starN = -1
        while (n < name.length) {
            if (p < pattern.length && (pattern[p] == '?' || pattern[p].equals(name[n], ignoreCase = true))) {
                p++; n++
            } else if (p < pattern.length && pattern[p] == '*') {
                starP = p++; starN = n
            } else if (starP != -1) {
                p = starP + 1; n = ++starN
            } else {
                return false
            }
        }
        while (p < pattern.length && pattern[p] == '*') p++
        return p == pattern.length
    }

    /** `~` 开头展开为 home 目录（OpenSSH 的 `~` 只在家目录位置有意义） */
    private fun expandTilde(path: String, homeDir: String?): String {
        if (homeDir == null) return path
        return when {
            path == "~" -> homeDir
            path.startsWith("~/") || path.startsWith("~\\") -> homeDir + path.substring(1)
            else -> path
        }
    }
}
