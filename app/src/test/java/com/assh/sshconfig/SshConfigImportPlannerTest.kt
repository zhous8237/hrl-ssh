package com.assh.sshconfig

import com.assh.data.db.entity.AuthType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshConfigImportPlannerTest {

    private val rsaPem = """
        -----BEGIN RSA PRIVATE KEY-----
        MIIBOgIBAAJBAKfake
        -----END RSA PRIVATE KEY-----
    """.trimIndent()

    private fun parsed(
        name: String,
        hostName: String = "10.0.0.1",
        user: String? = "root",
        port: Int = 22,
        identity: String? = null,
        unsupported: List<String> = emptyList()
    ) = SshConfigParser.ParsedHost(
        name = name,
        hostName = hostName,
        port = port,
        user = user,
        identityFile = identity,
        unsupportedOptions = unsupported
    )

    @Test
    fun `基本条目生成计划与主机实体`() {
        val plan = SshConfigImportPlanner.plan(listOf(parsed("web01")))
        val draft = plan.hosts.single()
        assertEquals("web01", draft.label)
        assertEquals("root", draft.username)
        assertEquals(22, draft.port)
        assertNull(draft.keyAlias)

        val entity = SshConfigImportPlanner.toHostEntity(draft, keyId = null)
        assertEquals(AuthType.PASSWORD, entity.authType)
        assertEquals("web01", entity.label)
    }

    @Test
    fun `同名的已有主机被跳过`() {
        val plan = SshConfigImportPlanner.plan(
            parsed = listOf(parsed("dup"), parsed("fresh")),
            existingLabels = setOf("dup")
        )
        assertEquals(listOf("fresh"), plan.hosts.map { it.label })
        assertTrue(plan.skipped.containsKey("dup"))
    }

    @Test
    fun `缺用户名时用默认用户名补齐，仍为空则跳过`() {
        val plan = SshConfigImportPlanner.plan(
            parsed = listOf(parsed("a", user = null), parsed("b", user = null)),
            defaultUsername = ""
        )
        assertTrue(plan.hosts.isEmpty())
        assertEquals(2, plan.skipped.size)

        val withDefault = SshConfigImportPlanner.plan(
            parsed = listOf(parsed("a", user = null)),
            defaultUsername = "deploy"
        )
        assertEquals("deploy", withDefault.hosts.single().username)
    }

    @Test
    fun `配置里没有 User 时取解析结果的首个用户名作为兜底`() {
        val plan = SshConfigImportPlanner.plan(
            parsed = listOf(parsed("a", user = "alice"), parsed("b", user = null)),
            defaultUsername = "alice"
        )
        assertEquals(listOf("alice", "alice"), plan.hosts.map { it.username })
    }

    @Test
    fun `IdentityFile 匹配到私钥内容后生成私钥计划并绑定`() {
        val plan = SshConfigImportPlanner.plan(
            parsed = listOf(parsed("db", identity = "/home/u/.ssh/id_rsa")),
            identityFileContents = mapOf("id_rsa" to rsaPem)
        )
        val key = plan.keys.single()
        assertEquals("id_rsa", key.alias)
        assertEquals("rsa", key.keyType)
        assertEquals("id_rsa", plan.hosts.single().keyAlias)
        assertTrue(plan.keyMissing.isEmpty())
    }

    @Test
    fun `IdentityFile 未提供内容时记录 missing 且不绑定`() {
        val plan = SshConfigImportPlanner.plan(
            parsed = listOf(parsed("db", identity = "C:\\keys\\prod_ed25519")),
            identityFileContents = emptyMap()
        )
        assertNull(plan.hosts.single().keyAlias)
        assertTrue(plan.keyMissing.containsKey("db"))
        assertTrue(plan.keys.isEmpty())
    }

    @Test
    fun `私钥别名冲突时自动加序号`() {
        val plan = SshConfigImportPlanner.plan(
            parsed = listOf(parsed("a", identity = "/k/id_rsa")),
            existingKeyAliases = setOf("id_rsa"),
            identityFileContents = mapOf("id_rsa" to rsaPem)
        )
        assertEquals("id_rsa (2)", plan.keys.single().alias)
    }

    @Test
    fun `同一私钥被多个主机引用时只生成一条私钥计划`() {
        val plan = SshConfigImportPlanner.plan(
            parsed = listOf(
                parsed("a", identity = "/k/id_rsa"),
                parsed("b", identity = "/k/id_rsa")
            ),
            identityFileContents = mapOf("id_rsa" to rsaPem)
        )
        assertEquals(1, plan.keys.size)
        assertEquals(listOf("id_rsa", "id_rsa"), plan.hosts.map { it.keyAlias })
    }

    @Test
    fun `忽略项透传到计划`() {
        val plan = SshConfigImportPlanner.plan(
            parsed = listOf(parsed("a", unsupported = listOf("proxyjump")))
        )
        assertEquals(listOf("proxyjump"), plan.hosts.single().ignoredOptions)
    }

    @Test
    fun `私钥类型按 PEM 头识别`() {
        assertEquals("openssh", SshConfigImportPlanner.guessKeyType("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertEquals("rsa", SshConfigImportPlanner.guessKeyType(rsaPem))
        assertEquals("ecdsa", SshConfigImportPlanner.guessKeyType("-----BEGIN EC PRIVATE KEY-----"))
        assertEquals("pkcs8", SshConfigImportPlanner.guessKeyType("-----BEGIN PRIVATE KEY-----"))
        assertEquals("unknown", SshConfigImportPlanner.guessKeyType("not a key"))
    }

    @Test
    fun `主机实体使用 KEY 认证当且仅当绑定成功`() {
        val plan = SshConfigImportPlanner.plan(
            parsed = listOf(parsed("a", identity = "/k/id_rsa")),
            identityFileContents = mapOf("id_rsa" to rsaPem)
        )
        val draft = plan.hosts.single()
        val entity = SshConfigImportPlanner.toHostEntity(draft, keyId = 42L)
        assertEquals(AuthType.KEY, entity.authType)
        assertEquals(42L, entity.keyId)
    }

    @Test
    fun `无密钥且开启无认证时按 NONE 导入`() {
        // instabox 这类：配置里没有 IdentityFile，用户名本身就是凭据
        val plan = SshConfigImportPlanner.plan(
            parsed = listOf(parsed("instabox-cli", user = "ibssh_abc"))
        )
        val draft = plan.hosts.single()
        assertNull(draft.keyAlias)
        val entity = SshConfigImportPlanner.toHostEntity(
            draft, keyId = null, noneAuthWhenNoKey = true
        )
        assertEquals(AuthType.NONE, entity.authType)
        assertNull(entity.keyId)
        assertEquals("instabox-cli", entity.label)
        assertEquals("ibssh_abc", entity.username)
    }

    @Test
    fun `未开启无认证时无密钥条目仍按密码导入`() {
        val plan = SshConfigImportPlanner.plan(parsed = listOf(parsed("plain")))
        val entity = SshConfigImportPlanner.toHostEntity(
            plan.hosts.single(), keyId = null, noneAuthWhenNoKey = false
        )
        assertEquals(AuthType.PASSWORD, entity.authType)
    }

    @Test
    fun `已绑定私钥时无认证开关不生效`() {
        val plan = SshConfigImportPlanner.plan(
            parsed = listOf(parsed("withkey", identity = "/k/id_rsa")),
            identityFileContents = mapOf("id_rsa" to rsaPem)
        )
        val entity = SshConfigImportPlanner.toHostEntity(
            plan.hosts.single(), keyId = 7L, noneAuthWhenNoKey = true
        )
        // 有私钥优先用 KEY，不能被 none 覆盖
        assertEquals(AuthType.KEY, entity.authType)
    }

    @Test
    fun `AuthType 依次含 PASSWORD KEY NONE`() {
        assertEquals(
            listOf(AuthType.PASSWORD, AuthType.KEY, AuthType.NONE),
            AuthType.values().toList()
        )
    }

    @Test
    fun `空解析结果产生空计划`() {
        val plan = SshConfigImportPlanner.plan(emptyList())
        assertEquals(0, plan.importableCount)
        assertEquals(0, plan.totalCount)
        assertTrue(plan.hosts.isEmpty() && plan.keys.isEmpty())
    }
}
