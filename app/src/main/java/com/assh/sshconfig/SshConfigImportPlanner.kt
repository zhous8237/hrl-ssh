package com.assh.sshconfig

import com.assh.data.db.entity.AuthType
import com.assh.data.db.entity.HostEntity
import com.assh.data.db.entity.KeyEntity

/**
 * SSH 配置 → 主机/私钥条目的“计划”（纯函数，不碰数据库）。
 *
 * 拆成两步的理由：UI 要先把「将创建什么」摆给用户确认，确认后才真正落库；
 * 计划阶段就可以把冲突、可导入/不可导入判清楚，落库阶段只做纯写入。
 */
object SshConfigImportPlanner {

    /** 计划中的一条主机（对应一个 `Host` 别名） */
    data class HostDraft(
        val label: String,
        val host: String,
        val port: Int,
        val username: String,
        /** 引用的私钥别名；null = 配置未指定 IdentityFile */
        val keyAlias: String?,
        /** 该条目忽略了哪些配置项，用于 UI 提示 */
        val ignoredOptions: List<String> = emptyList()
    )

    /** 计划中的一条私钥（`IdentityFile` 去重后） */
    data class KeyDraft(
        val alias: String,
        /** 配置中写的路径（原样，便于用户核对来源） */
        val sourcePath: String,
        val keyType: String
    )

    data class Plan(
        val hosts: List<HostDraft>,
        /** 需要用户手动补私钥的条目：别名 → 原因 */
        val keyMissing: Map<String, String> = emptyMap(),
        /** 跳过项（别名 → 原因），如重名、无用户名 */
        val skipped: Map<String, String> = emptyMap(),
        val keys: List<KeyDraft> = emptyList()
    ) {
        val importableCount: Int get() = hosts.size
        val totalCount: Int get() = hosts.size + skipped.size
    }

    /**
     * 根据解析结果生成导入计划。
     *
     * @param parsed 解析器输出
     * @param existingLabels 已存在的主机显示名（用于去重，避免重复导入同一别名）
     * @param existingKeyAliases 已存在的私钥别名（用于避免别名冲突）
     * @param identityFileContents 用户额外提供的私钥文本：路径 → PEM 内容。
     *        配置里的 `IdentityFile` 只是路径，Android 上无法直接读 PC 的文件，
     *        因此用户可把私钥文件一并导入，这里按路径匹配后再绑定。
     * @param defaultUsername 配置里缺 `User` 时使用的兜底用户名（可为空 → 该条目跳过）
     */
    fun plan(
        parsed: List<SshConfigParser.ParsedHost>,
        existingLabels: Set<String> = emptySet(),
        existingKeyAliases: Set<String> = emptySet(),
        identityFileContents: Map<String, String> = emptyMap(),
        defaultUsername: String = ""
    ): Plan {
        val hosts = mutableListOf<HostDraft>()
        val keyMissing = LinkedHashMap<String, String>()
        val skipped = LinkedHashMap<String, String>()
        val keyDrafts = LinkedHashMap<String, KeyDraft>()
        val usedAliases = existingKeyAliases.toMutableSet()
        val usedLabels = existingLabels.toMutableSet()
        // IdentityFile 路径 → 已分配的私钥别名（同一把私钥只建一条）
        val pathToAlias = LinkedHashMap<String, String>()

        for (p in parsed) {
            val label = p.name.trim()
            if (label.isEmpty()) continue
            if (label in usedLabels) {
                skipped[label] = "已存在同名主机，跳过"
                continue
            }

            val username = (p.user ?: defaultUsername).trim()
            if (username.isEmpty()) {
                skipped[label] = "配置未指定 User，本 App 无服务器级默认用户名，需手动补"
                continue
            }

            if (p.hostName.isBlank()) {
                skipped[label] = "HostName 为空"
                continue
            }

            var keyAlias: String? = null
            val identity = p.identityFile
            if (identity != null) {
                val content = matchIdentityContent(identity, identityFileContents)
                if (content == null) {
                    keyMissing[label] = "私钥 $identity 未随配置一起导入（Android 无法读取 PC 路径）"
                } else {
                    // 同一路径（同一把私钥）被多个主机引用时复用同一别名，避免重复建私钥条目
                    val alias = pathToAlias[identity] ?: uniqueAlias(fileStem(identity), usedAliases).also { a ->
                        usedAliases += a
                        pathToAlias[identity] = a
                    }
                    keyDrafts.getOrPut(alias) {
                        KeyDraft(alias = alias, sourcePath = identity, keyType = guessKeyType(content))
                    }
                    keyAlias = alias
                }
            }

            usedLabels += label
            hosts += HostDraft(
                label = label,
                host = p.hostName,
                port = p.port,
                username = username,
                keyAlias = keyAlias,
                ignoredOptions = p.unsupportedOptions
            )
        }

        return Plan(
            hosts = hosts,
            keyMissing = keyMissing,
            skipped = skipped,
            keys = keyDrafts.values.toList()
        )
    }

    /**
     * 用计划生成可落库的实体（id = 0，时间戳取当前）。keyId 由调用方按别名回填。
     *
     * @param noneAuthWhenNoKey 没有绑定私钥时是否按 `none` 认证（用户名即凭据）落库。
     *        导入面板在「配置里没有 IdentityFile」时会询问用户并置 true，
     *        避免像 instabox 这类服务被建成密码认证后在连接时报「密码未提供」。
     */
    fun toHostEntity(
        draft: HostDraft,
        keyId: Long?,
        sortOrder: Int = 0,
        noneAuthWhenNoKey: Boolean = false
    ): HostEntity =
        HostEntity(
            label = draft.label,
            host = draft.host,
            port = draft.port,
            username = draft.username,
            authType = when {
                keyId != null -> AuthType.KEY
                noneAuthWhenNoKey -> AuthType.NONE
                else -> AuthType.PASSWORD
            },
            keyId = keyId,
            sortOrder = sortOrder
        )

    fun toKeyEntity(draft: KeyDraft, encryptedPem: ByteArray): KeyEntity =
        KeyEntity(
            alias = draft.alias,
            keyType = draft.keyType,
            encPrivateKey = encryptedPem,
            sourceName = draft.sourcePath
        )

    // ===== 内部工具 =====

    /** 把配置里的 IdentityFile 路径匹配到用户提供的私钥内容（容忍绝对/相对/反斜杠差异） */
    private fun matchIdentityContent(identity: String, contents: Map<String, String>): String? {
        val target = identity.replace('\\', '/').trim()
        contents.entries.firstOrNull { it.key.equals(identity, ignoreCase = true) }?.let { return it.value }
        return contents.entries.firstOrNull { e ->
            val k = e.key.replace('\\', '/').trim()
            k == target || k.endsWith("/" + target.trimStart('/')) || target.endsWith("/" + k.trimStart('/'))
        }?.value
    }

    private fun uniqueAlias(desired: String, used: Set<String>): String {
        val base = desired.ifBlank { "key" }
        if (base !in used) return base
        var n = 2
        while ("$base ($n)" in used) n++
        return "$base ($n)"
    }

    private fun fileStem(path: String): String {
        val normalized = path.replace('\\', '/').trimEnd('/')
        return normalized.substringAfterLast('/').ifBlank { "key" }
    }

    /** 与 [com.assh.data.repo.KeyRepository.guessKeyType] 保持一致的头部判断 */
    fun guessKeyType(pem: String): String {
        val head = pem.trim().lineSequence().take(2).joinToString(" ")
        return when {
            head.contains("OPENSSH PRIVATE KEY") -> "openssh"
            head.contains("RSA PRIVATE KEY") -> "rsa"
            head.contains("EC PRIVATE KEY") -> "ecdsa"
            head.contains("DSA PRIVATE KEY") -> "dsa"
            head.contains("PRIVATE KEY") -> "pkcs8"
            else -> "unknown"
        }
    }
}
