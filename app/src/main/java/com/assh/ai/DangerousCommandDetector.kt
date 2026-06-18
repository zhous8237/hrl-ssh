package com.assh.ai

/** 命令风险等级 */
enum class RiskLevel { SAFE, DANGEROUS }

/** 危险命令分类结果。[matchedRule]/[reason] 供 UI 向用户解释为何需要确认 */
data class Classification(
    val level: RiskLevel,
    val matchedRule: String? = null,
    val reason: String? = null
)

/**
 * 危险命令静态检测（纯函数，无副作用）。命中任一规则即判 DANGEROUS，
 * 由引擎暂停等用户确认；其余默认 SAFE（符合"只拦明确危险"的产品决策）。
 *
 * 两层防御：
 * 1. 规则层：对命令文本直接正则匹配高危类别（删除 / 格式化 / 关机 / 覆盖系统文件 / …）。
 * 2. 解包层：对 `sh -c "…"`、`bash -c '…'`、`eval …` 包裹的内层命令递归再判，
 *    堵住 `bash -c "rm -rf /"` 这类把危险命令藏进字符串里绕过规则层的写法。
 *
 * 仍有局限：变量展开（rm -rf $X）、别名、运行时拼接无法静态识别——宁可漏放，不做大面积误拦。
 * 这是"尽量拦明确危险"的闸门，不是沙箱；最终兜底是用户的确认策略（见 [ConfirmPolicy]，
 * 设为 ALWAYS 时无论本检测结果如何，每条命令都需人工确认）。
 */
object DangerousCommandDetector {

    private data class Rule(val name: String, val reason: String, val regex: Regex)

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    private val rules: List<Rule> = listOf(
        Rule(
            "递归删除",
            "rm 携带递归选项（-r/-R/--recursive），可能抹掉整个目录树",
            // 只要 rm 带任一含 r/R 的短选项或 --recursive 即判危险（不再要求同时带 -f）。
            // [^;|&\n]*? 限定在同一简单命令内，避免跨管道/分号误匹配下一条命令。
            rx("""\brm\b[^;|&\n]*?\s(-[a-zA-Z]*[rR][a-zA-Z]*|--recursive)\b""")
        ),
        Rule(
            "批量查找删除",
            "find 配合 -delete / -exec rm 会批量删除匹配到的文件",
            rx("""\bfind\b[^\n]*\s(-delete\b|-exec\s+rm\b|-execdir\s+rm\b)""")
        ),
        Rule(
            "不可恢复擦除",
            "shred 会反复覆盖文件内容使其不可恢复",
            rx("""\bshred\b""")
        ),
        Rule(
            "格式化 / 裸写磁盘",
            "格式化或直接写裸设备会永久销毁分区数据",
            rx("""\b(mkfs(\.\w+)?|mke2fs|wipefs|fdisk|parted|sgdisk)\b|\bdd\b[^\n]*\bof=|>\s*/dev/(sd|nvme|vd|hd)""")
        ),
        Rule(
            "关机 / 重启",
            "关机或重启会中断服务并断开当前连接",
            rx("""\b(shutdown|reboot|halt|poweroff)\b|\binit\s+[06]\b""")
        ),
        Rule(
            "Fork bomb",
            "fork 炸弹会瞬间耗尽进程资源使系统失去响应",
            rx(""":\s*\(\s*\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:|\(\)\s*\{[^}]*\|[^}]*&[^}]*\}\s*;""")
        ),
        Rule(
            "递归改权限 / 属主到系统目录",
            "对 /etc /usr /var 等系统目录递归改权限会破坏系统",
            rx("""\b(chmod|chown)\b[^\n]*-R[^\n]*\s/(etc|usr|var|bin|boot|lib|sbin|root|sys|proc)(/|\s|$)|\bchmod\b[^\n]*-R[^\n]*\s777\s+/""")
        ),
        Rule(
            "覆盖系统关键文件",
            "重定向覆盖 /etc 下关键文件或引导分区会导致系统无法启动 / 登录",
            rx(""">\s*/(etc|boot)/|>\s*/etc/(passwd|shadow|fstab|sudoers)|\btruncate\b[^\n]*\s/(etc|boot)/""")
        ),
        Rule(
            "远程脚本直接执行",
            "把下载内容直接管道给 shell 执行，未经审查即运行任意代码",
            rx("""\b(curl|wget)\b[^\n]*\|\s*(sudo\s+)?(sh|bash|zsh|dash)\b""")
        ),
        Rule(
            "解码后直接执行",
            "把 base64/解码内容直接管道给 shell 执行，等于运行被隐藏的任意代码",
            rx("""\bbase64\b[^\n]*(-d|--decode)\b[^\n]*\|\s*(sudo\s+)?(sh|bash|zsh|dash)\b""")
        ),
        Rule(
            "破坏用户 / 网络 / SSH 服务",
            "删用户、清空防火墙或停用 SSH 可能导致失联或权限丢失",
            rx("""\b(userdel|groupdel)\b|\biptables\b[^\n]*\s-F\b|\bsystemctl\b\s+(stop|disable)\s+(ssh|sshd)\b|\bufw\b\s+disable""")
        ),
        Rule(
            "卸载核心系统包",
            "移除 systemd / 内核 / libc 等核心包会使系统不可用",
            rx("""\b(apt-get|apt|dnf|yum)\b[^\n]*\b(remove|purge|erase|autoremove)\b[^\n]*\b(systemd|linux-image|kernel|libc6|glibc|coreutils|bash)\b""")
        )
    )

    /** 解包间接执行：匹配到则 group(1) 是被包裹的内层命令文本（含可能的引号） */
    private val indirectRules: List<Regex> = listOf(
        // sh -c / bash -c / zsh -c / dash -c / ash -c "<inner>"
        rx("""\b(?:ba|z|da|a)?sh\b(?:\s+-[a-z]+)*\s+-c\s+(.+)"""),
        // eval <inner>
        rx("""\beval\s+(.+)""")
    )

    fun classify(command: String): Classification = classifyInternal(command.trim(), depth = 0)

    private fun classifyInternal(cmd: String, depth: Int): Classification {
        for (r in rules) {
            if (r.regex.containsMatchIn(cmd)) {
                return Classification(RiskLevel.DANGEROUS, r.name, r.reason)
            }
        }
        // 解包 sh -c / eval 的内层命令再判，堵间接执行绕过；限深度防递归失控
        if (depth < 3) {
            for (re in indirectRules) {
                val inner = re.find(cmd)?.groupValues?.getOrNull(1)?.let(::stripQuotes) ?: continue
                if (inner.isBlank() || inner == cmd) continue
                val c = classifyInternal(inner, depth + 1)
                if (c.level == RiskLevel.DANGEROUS) {
                    return c.copy(reason = (c.reason ?: "") + "（被包裹在 sh -c / eval 等间接执行中）")
                }
            }
        }
        return Classification(RiskLevel.SAFE)
    }

    /** 去掉最外层成对引号，便于对内层命令再次匹配 */
    private fun stripQuotes(s: String): String {
        val t = s.trim()
        if (t.length >= 2) {
            val q = t.first()
            if ((q == '\'' || q == '"') && t.last() == q) return t.substring(1, t.length - 1)
        }
        return t
    }

    fun isDangerous(command: String): Boolean =
        classify(command).level == RiskLevel.DANGEROUS
}
