package com.assh.ui.terminal

/**
 * SSH / 网络错误信息离线翻译器（功能 5）：
 * 连接报错均为英文（sshj / java.net 抛出），按关键词匹配给出中文释义与排查建议。
 * 无网络依赖，实时返回；未命中规则时返回 null（UI 只展示原文）。
 */
object SshErrorTranslator {

    private data class Rule(val keywords: List<String>, val translation: String)

    /** 规则按优先级排列，先命中先返回；关键词全部小写匹配 */
    private val rules = listOf(
        Rule(
            listOf("auth fail", "authentication failed", "exhausted available authentication"),
            "认证失败：用户名、密码或私钥不正确，请检查凭据设置。"
        ),
        Rule(
            listOf("permission denied"),
            "权限被拒绝：服务器拒绝了该用户的登录，请确认用户名/密码/私钥是否正确，或服务器是否禁止该认证方式。"
        ),
        Rule(
            listOf("connection refused", "econnrefused"),
            "连接被拒绝：目标端口没有服务在监听。请确认 IP 与端口正确、服务器 sshd 已启动、防火墙未拦截。"
        ),
        Rule(
            listOf("timed out", "timeout", "etimedout"),
            "连接超时：无法在限定时间内连上服务器。请检查网络是否可达、IP/端口是否正确、防火墙或安全组是否放行。"
        ),
        Rule(
            listOf("unknownhostexception", "unable to resolve host", "no address associated", "name or service not known"),
            "域名解析失败：无法将主机名解析为 IP。请检查主机地址拼写，或当前网络的 DNS 设置。"
        ),
        Rule(
            listOf("network is unreachable", "enetunreach"),
            "网络不可达：当前设备没有可用路由到目标地址。请检查手机网络连接（IPv6 地址需要当前网络支持 IPv6）。"
        ),
        Rule(
            listOf("no route to host", "ehostunreach"),
            "无法路由到主机：目标地址不可达，可能是服务器关机、IP 错误或中间防火墙丢弃了数据包。"
        ),
        Rule(
            listOf("connection reset", "econnreset"),
            "连接被重置：服务器或中间设备主动断开了连接，可能是 sshd 限制（MaxStartups/防爆破）或网络设备干预。"
        ),
        Rule(
            listOf("broken pipe", "epipe"),
            "连接中断（Broken pipe）：写入时对端已关闭连接，通常是网络闪断或服务器主动踢出。"
        ),
        Rule(
            listOf("host key", "hostkey"),
            "主机指纹问题：服务器身份指纹校验未通过。若非服务器重装，请警惕中间人攻击。"
        ),
        Rule(
            listOf("could not load key", "keyparse", "invalid key", "bad key", "unsupported key"),
            "私钥解析失败：私钥格式不受支持或内容损坏，请确认粘贴完整（含 BEGIN/END 行）且格式为 PEM/OpenSSH。"
        ),
        Rule(
            listOf("passphrase", "decrypt"),
            "私钥口令错误：无法解密私钥，请检查私钥口令是否正确。"
        ),
        Rule(
            listOf("connection closed", "disconnected", "eof"),
            "连接已关闭：服务器在握手或会话过程中断开。可能是认证次数超限、sshd 配置限制或网络中断。"
        ),
        Rule(
            listOf("algorithm", "kex", "cipher", "negotiation"),
            "算法协商失败：客户端与服务器没有共同支持的加密/密钥交换算法，服务器可能过旧或配置受限。"
        ),
        Rule(
            listOf("socket is closed", "socket closed", "socketexception"),
            "套接字异常：底层网络连接已失效，请重试；若频繁出现请检查网络稳定性。"
        ),
        Rule(
            listOf("too many authentication failures"),
            "认证失败次数过多：服务器已拒绝继续尝试，请核对凭据后稍后再试。"
        ),
        Rule(
            listOf("banner", "protocol"),
            "协议异常：对端返回的不是 SSH 协议数据，请确认连接的端口确实是 SSH 服务端口。"
        ),
    )

    /** 返回中文释义；无匹配返回 null */
    fun translate(error: String): String? {
        val lower = error.lowercase()
        return rules.firstOrNull { rule -> rule.keywords.any { lower.contains(it) } }?.translation
    }
}
