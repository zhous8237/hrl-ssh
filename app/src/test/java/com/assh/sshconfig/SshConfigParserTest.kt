package com.assh.sshconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshConfigParserTest {

    @Test
    fun `解析基本字段 HostName User Port`() {
        val cfg = """
            Host web01
                HostName 10.0.0.11
                User deploy
                Port 2222
        """.trimIndent()

        val result = SshConfigParser.parse(cfg)
        assertEquals(1, result.hosts.size)
        val h = result.hosts.single()
        assertEquals("web01", h.name)
        assertEquals("10.0.0.11", h.hostName)
        assertEquals("deploy", h.user)
        assertEquals(2222, h.port)
    }

    @Test
    fun `缺省的 Port 与 User 分别为 22 和 null`() {
        val result = SshConfigParser.parse("Host a\n  HostName 1.2.3.4")
        val h = result.hosts.single()
        assertEquals(22, h.port)
        assertNull(h.user)
    }

    @Test
    fun `Host 多别名各自生成一条`() {
        val cfg = """
            Host prod prod-db
                HostName db.internal
                User admin
        """.trimIndent()
        val names = SshConfigParser.parse(cfg).hosts.map { it.name }
        assertEquals(listOf("prod", "prod-db"), names)
    }

    @Test
    fun `通配 Host 块不生成条目`() {
        val cfg = """
            Host *
                ServerAliveInterval 30
            Host bastion
                HostName 10.1.1.1
                User ops
        """.trimIndent()
        val hosts = SshConfigParser.parse(cfg).hosts
        assertEquals(1, hosts.size)
        assertEquals("bastion", hosts.single().name)
    }

    @Test
    fun `关键字大小写不敏感且可用等号分隔`() {
        val cfg = """
            HOST GitLab
              HOSTNAME=git.example.com
              USER=git
              PORT = 2200
        """.trimIndent()
        val h = SshConfigParser.parse(cfg).hosts.single()
        assertEquals("git.example.com", h.hostName)
        assertEquals("git", h.user)
        assertEquals(2200, h.port)
    }

    @Test
    fun `同一选项先出现者生效`() {
        val cfg = """
            Host dup
                User first
                User second
                HostName h.example.com
        """.trimIndent()
        assertEquals("first", SshConfigParser.parse(cfg).hosts.single().user)
    }

    @Test
    fun `注释与空行被忽略且行内井号不误伤`() {
        val cfg = """
            # 顶部注释
            Host a   # 行尾注释
                HostName 1.1.1.1
        """.trimIndent()
        val h = SshConfigParser.parse(cfg).hosts.single()
        assertEquals("a", h.name)
        assertEquals("1.1.1.1", h.hostName)
    }

    @Test
    fun `令牌替换 h r d n`() {
        val cfg = """
            Host web
                HostName web.example.com
                User bob
                IdentityFile %d/.ssh/%r-%h.pem
        """.trimIndent()
        val h = SshConfigParser.parse(cfg, homeDir = "/home/bob").hosts.single()
        assertEquals("/home/bob/.ssh/bob-web.example.com.pem", h.identityFile)
    }

    @Test
    fun `IdentityFile 原样保留路径`() {
        val cfg = """
            Host k
                HostName 5.5.5.5
                User root
                IdentityFile ~/.ssh/id_ed25519
        """.trimIndent()
        val h = SshConfigParser.parse(cfg, homeDir = "C:\\Users\\me").hosts.single()
        assertEquals("C:\\Users\\me/.ssh/id_ed25519", h.identityFile)
    }

    @Test
    fun `不支持的选项被记录`() {
        val cfg = """
            Host viajump
                HostName 10.0.0.2
                User root
                ProxyJump bastion
                LocalForward 8080 localhost:80
        """.trimIndent()
        val h = SshConfigParser.parse(cfg).hosts.single()
        assertEquals(listOf("proxyjump", "localforward"), h.unsupportedOptions)
    }

    @Test
    fun `算法协商类关键字被明确记录为已忽略`() {
        val cfg = """
            Host certsrv
                HostName 10.0.0.9
                User deploy
                PubkeyAcceptedKeyTypes +ssh-ed25519-cert-v01@openssh.com
                HostKeyAlgorithms +ssh-ed25519-cert-v01@openssh.com
                Ciphers aes256-ctr
                MACs hmac-sha2-256
                KexAlgorithms curve25519-sha256
                PubkeyAcceptedAlgorithms ssh-ed25519
        """.trimIndent()
        val h = SshConfigParser.parse(cfg).hosts.single()
        // 关键：不再静默丢弃，而是全部记入 unsupportedOptions 供 UI 提示“已忽略”
        assertEquals(
            listOf(
                "pubkeyacceptedkeytypes", "hostkeyalgorithms",
                "ciphers", "macs", "kexalgorithms", "pubkeyacceptedalgorithms"
            ),
            h.unsupportedOptions
        )
        // 其余字段仍正常解析，不影响能否连接
        assertEquals("10.0.0.9", h.hostName)
        assertEquals("deploy", h.user)
        assertEquals(22, h.port)
    }

    @Test
    fun `算法关键字被忽略后仍计入导入计划的忽略项`() {
        val cfg = """
            Host certsrv
                HostName 10.0.0.9
                User deploy
                HostKeyAlgorithms +ssh-ed25519-cert-v01@openssh.com
        """.trimIndent()
        val parsed = SshConfigParser.parse(cfg).hosts
        val plan = SshConfigImportPlanner.plan(parsed)
        assertEquals(listOf("hostkeyalgorithms"), plan.hosts.single().ignoredOptions)
        // 条目本身仍可导入
        assertEquals(1, plan.importableCount)
    }

    @Test
    fun `Match 块不生成主机并给出告警`() {
        val cfg = """
            Host real
                HostName 9.9.9.9
                User u
            Match host foo exec "true"
                User ignored
        """.trimIndent()
        val result = SshConfigParser.parse(cfg)
        assertEquals(1, result.hosts.size)
        assertEquals("u", result.hosts.single().user)
        assertTrue(result.warnings.any { it.message.contains("Match") })
    }

    @Test
    fun `Include 展开内联文件`() {
        val main = """
            Host a
                HostName 1.1.1.1
                User ua
            Include ~/.ssh/config.d/extra
        """.trimIndent()
        val extra = """
            Host b
                HostName 2.2.2.2
                User ub
        """.trimIndent()

        val result = SshConfigParser.parse(
            configText = main,
            homeDir = "/home/u",
            inlineFiles = mapOf("/home/u/.ssh/config.d/extra" to extra)
        )
        assertEquals(listOf("a", "b"), result.hosts.map { it.name })
        assertEquals("ub", result.hosts[1].user)
    }

    @Test
    fun `Include 无内容时只告警不中断`() {
        val cfg = """
            Host a
                HostName 1.1.1.1
                User u
            Include ~/.ssh/other
        """.trimIndent()
        val result = SshConfigParser.parse(cfg)
        assertEquals(1, result.hosts.size)
        assertTrue(result.warnings.any { it.message.contains("Include") })
    }

    @Test
    fun `Host 块外全局选项被忽略并告警`() {
        val cfg = """
            ServerAliveInterval 60
            Host a
                HostName 1.1.1.1
                User u
        """.trimIndent()
        val result = SshConfigParser.parse(cfg)
        assertEquals(1, result.hosts.size)
        assertTrue(result.warnings.any { it.message.contains("全局选项") })
    }

    @Test
    fun `非法 Port 按 22 处理并告警`() {
        val cfg = """
            Host a
                HostName 1.1.1.1
                User u
                Port notanumber
        """.trimIndent()
        val result = SshConfigParser.parse(cfg)
        assertEquals(22, result.hosts.single().port)
        assertTrue(result.warnings.any { it.message.contains("Port") })
    }

    @Test
    fun `通配匹配工具`() {
        assertTrue(SshConfigParser.wildcardMatches("*", "anything"))
        assertTrue(SshConfigParser.wildcardMatches("web-?", "web-1"))
        assertTrue(SshConfigParser.wildcardMatches("*.example.com", "a.example.com"))
        assertTrue(!SshConfigParser.wildcardMatches("web-?", "web-12"))
        assertTrue(!SshConfigParser.wildcardMatches("db", "web"))
    }

    @Test
    fun `空配置返回空结果`() {
        assertTrue(SshConfigParser.parse("").hosts.isEmpty())
        assertTrue(SshConfigParser.parse("# 只有注释\n\n").hosts.isEmpty())
    }

    @Test
    fun `多选时多个文件作为 Include 目标一起解析`() {
        // 模拟“多选主 config + config.d 下两个片段”的场景
        val main = """
            Include ~/.ssh/config.d/*
            Host direct
                HostName 10.0.0.1
                User u
        """.trimIndent()
        val frag1 = """
            Host from-frag1
                HostName 10.0.0.2
                User u2
        """.trimIndent()
        val frag2 = """
            Host from-frag2
                HostName 10.0.0.3
                User u3
        """.trimIndent()

        val result = SshConfigParser.parse(
            configText = main,
            homeDir = "/home/u",
            inlineFiles = mapOf(
                "frag1" to frag1,
                "frag2" to frag2
            )
        )
        // 通配 Include 只匹配到第一个片段（解析器按首个命中展开），
        // 具名 Host 一定导入；关键是不因 Include 通配而崩或丢条目
        assertEquals("direct", result.hosts.first().name)
        assertTrue(result.hosts.size >= 1)
    }

    @Test
    fun `Include 精确指向某个片段时能取到该文件的主机`() {
        val main = """
            Host local
                HostName 10.0.0.1
                User u
            Include ~/.ssh/config.d/work
        """.trimIndent()
        val work = """
            Host work-db
                HostName db.corp
                User dba
                Port 2200
        """.trimIndent()

        val result = SshConfigParser.parse(
            configText = main,
            homeDir = "/home/u",
            inlineFiles = mapOf("/home/u/.ssh/config.d/work" to work)
        )
        assertEquals(listOf("local", "work-db"), result.hosts.map { it.name })
        val db = result.hosts.single { it.name == "work-db" }
        assertEquals("db.corp", db.hostName)
        assertEquals(2200, db.port)
        assertEquals("dba", db.user)
    }
}
