package com.assh.ai.ssh

import com.assh.ssh.HostKeyChangedException

/** SSH 错误的重试取向：可退避重连 vs 致命（须用户处理）。 */
enum class SshErrorKind { TRANSIENT, FATAL }

/**
 * 把建连/重连抛出的异常分类为「可重连」或「致命」（纯函数，遍历整条 cause 链）。
 *
 * - FATAL（立即停、重试无益）：认证失败 / 权限拒绝 / 密钥解析或口令错 / 算法协商失败 /
 *   host key 变更 / 缺少凭据。这类反复重试只会触发服务器「认证次数过多」甚至锁号，须用户处理。
 * - TRANSIENT（退避重连）：连接被关/重置/EOF/broken pipe、超时、拒绝、不可达、未连接等
 *   传输/网络层错误——含 AI 自己 `reboot` / 重启 sshd 后的暂时不可达，退避等它回来。
 *
 * 取向：**未知一律判 TRANSIENT**（韧性优先）。致命错都有明确特征；漏判顶多多重试一轮，
 * 被上层重连预算兜住，不会无限重试。sshj 常把根因（EOF 等）包在 SSHException 里，故须遍历 cause。
 */
object SshErrorClassifier {

    /** 致命异常类名（simpleName，小写包含匹配）：避免硬依赖各 sshj 异常类的精确包路径。 */
    private val fatalClassNames = listOf(
        "userauthexception",            // net.schmizz.sshj.userauth.UserAuthException
        "keydecryptionfailedexception"  // 私钥口令错
    )

    /** 致命关键字（命中异常 message，小写匹配）。 */
    private val fatalKeywords = listOf(
        "auth fail", "authentication fail", "exhausted available authentication",
        "too many authentication failures", "permission denied", "publickey",
        "could not load", "could not parse", "invalid privatekey", "unable to load key",
        "passphrase", "decrypt",
        "no common", "negotiation fail", "key exchange", "no suitable", "unsupported",
        "密码未提供", "私钥未提供", "密钥未提供"
    )

    fun classify(error: Throwable): SshErrorKind {
        var t: Throwable? = error
        var depth = 0
        while (t != null && depth < 16) {   // 限深防自引用/超长 cause 链
            if (t is HostKeyChangedException) return SshErrorKind.FATAL
            val cls = t.javaClass.simpleName.lowercase()
            if (fatalClassNames.any { cls.contains(it) }) return SshErrorKind.FATAL
            val msg = t.message?.lowercase()
            if (msg != null && fatalKeywords.any { msg.contains(it) }) return SshErrorKind.FATAL
            t = t.cause
            depth++
        }
        return SshErrorKind.TRANSIENT
    }
}
