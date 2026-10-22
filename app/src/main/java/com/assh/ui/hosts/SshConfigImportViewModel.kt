package com.assh.ui.hosts

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.assh.AsshApp
import com.assh.data.crypto.CryptoManager
import com.assh.sshconfig.SshConfigImportPlanner
import com.assh.sshconfig.SshConfigParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SSH 配置批量导入（`~/.ssh/config` 形态）。
 *
 * 三步：选配置文件 → 预览解析结果（可补私钥）→ 确认写入。
 * 解析与落库都在 IO 线程，UI 只拿最终结果；私钥入库前经 CryptoManager 加密。
 */
class SshConfigImportViewModel(app: Application) : AndroidViewModel(app) {

    private val asshApp = app as AsshApp
    private val hostRepo = asshApp.hostRepository
    private val keyRepo = asshApp.keyRepository

    data class State(
        val busy: Boolean = false,
        val fileName: String? = null,
        /** 一并选中、供 Include 使用的附加配置文件（展示名列表） */
        val extraFiles: List<String> = emptyList(),
        val plan: SshConfigImportPlanner.Plan? = null,
        val warnings: List<String> = emptyList(),
        val defaultUsername: String = "",
        /** 已随配置一起提供的私钥：展示名 → 内容（明文只在本状态里，落库后丢弃） */
        val identities: Map<String, String> = emptyMap(),
        /** Include 用的附加配置文件：展示名 → 全文（与 identities 分开，避免误判为私钥） */
        val includeFiles: Map<String, String> = emptyMap(),
        /** 没有绑定私钥的条目是否按 none 认证（用户名即凭据）导入 */
        val noneAuthWhenNoKey: Boolean = false,
        val error: String? = null,
        val doneMessage: String? = null
    )

    fun setNoneAuthWhenNoKey(enabled: Boolean) {
        _state.value = _state.value.copy(noneAuthWhenNoKey = enabled)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var configText: String = ""
    /** Include 目标：展示名 → 全文（可由多选一并读入） */
    private var includeTexts: Map<String, String> = emptyMap()

    fun update(action: State.() -> State) {
        _state.value = _state.value.action()
    }

    fun setDefaultUsername(name: String) {
        _state.value = _state.value.copy(defaultUsername = name)
        replan()
    }

    fun clearError() = update { copy(error = null) }

    fun clearDone() = update { copy(doneMessage = null) }

    /** 读取配置文件并解析出预览计划 */
    /**
     * 读取配置文件（支持多选）。
     *
     * 多选语义：含 `Host` 行的文件视为主配置，其余作为 Include 目标一并用上；
     * 若选中多个都含 `Host` 的文件，则按选择顺序全部作为主配置（后面文件的 Host 照样导入）。
     * 私钥文件请用 [addIdentityFile] 选择，避免被当作配置文本解析。
     */
    fun loadConfigs(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                val read = withContext(Dispatchers.IO) {
                    uris.mapNotNull { u ->
                        runCatching { readUri(u) }.getOrNull()
                    }
                }
                if (read.isEmpty()) {
                    _state.value = _state.value.copy(busy = false, error = "读取失败：没有可用文件")
                    return@launch
                }

                // 用“是否含 Host/Match 行”区分主配置与 Include 片段
                val mains = read.filter { (_, text) -> hasHostSection(text) }
                val fragments = read.filterNot { (_, text) -> hasHostSection(text) }

                val mainList = mains.ifEmpty { read.take(1) }
                val mainText = mainList.joinToString("\n") { it.second }

                configText = mainText
                includeTexts = fragments.associate { it.first to it.second }
                _state.value = _state.value.copy(
                    busy = false,
                    fileName = mainList.first().first,
                    extraFiles = fragments.map { it.first } + mainList.drop(1).map { it.first },
                    includeFiles = includeTexts,
                    plan = null
                )
                replan()
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = "读取失败：${e.message}")
            }
        }
    }

    /** 一个 config 片段里是否真的定义了主机（用于区分主配置与纯 Include 片段） */
    private fun hasHostSection(text: String): Boolean =
        text.lineSequence().any { line ->
            val t = line.trim()
            t.startsWith("Host ", ignoreCase = true) || t.startsWith("Host=", ignoreCase = true) ||
                t.equals("Host", ignoreCase = true) ||
                t.startsWith("Match ", ignoreCase = true) || t.equals("Match", ignoreCase = true)
        }

    /** 追加一个私钥文件（配置里只写了路径，内容需要用户单独给） */
    fun addIdentityFile(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = LinkedHashMap<String, String>()
            val failed = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    runCatching {
                        val (name, text) = readUri(uri)
                        if (text.contains("PRIVATE KEY")) {
                            synchronized(added) { added[name] = text }
                        } else {
                            synchronized(failed) { failed += "$name（不是私钥文本）" }
                        }
                    }.onFailure {
                        synchronized(failed) { failed += "${uri.lastPathSegment}（${it.message}）" }
                    }
                }
            }
            _state.value = _state.value.copy(
                identities = _state.value.identities + added,
                error = if (failed.isEmpty()) _state.value.error else "已跳过：" + failed.joinToString("；")
            )
            replan()
        }
    }

    fun removeIdentity(name: String) {
        _state.value = _state.value.copy(identities = _state.value.identities - name)
        replan()
    }

    /** 重新生成预览计划（不改动已选文件与已加私钥） */
    fun replan() {
        if (configText.isBlank()) return
        viewModelScope.launch {
            val s = _state.value
            val result = withContext(Dispatchers.IO) {
                SshConfigParser.parse(
                    configText = configText,
                    homeDir = null,
                    // 多选来的其余文件交给 Include 解析：路径按文件名尾段匹配
                    inlineFiles = includeTexts,
                    sourceName = s.fileName ?: "config"
                )
            }
            val labels = withContext(Dispatchers.IO) {
                asshApp.database.hostDao().getAll().map { it.label }.toSet()
            }
            val aliases = withContext(Dispatchers.IO) {
                asshApp.database.keyDao().getAll().map { it.alias }.toSet()
            }
            val plan = SshConfigImportPlanner.plan(
                parsed = result.hosts,
                existingLabels = labels,
                existingKeyAliases = aliases,
                identityFileContents = s.identities,
                defaultUsername = s.defaultUsername.ifBlank { defaultUserGuess(result.hosts) }
            )
            _state.value = s.copy(
                plan = plan,
                warnings = result.warnings.map { w ->
                    if (w.file != null && w.line > 0) "${w.file}:${w.line}  ${w.message}" else w.message
                }
            )
        }
    }

    /** 确认导入：先写私钥拿 id，再写主机引用它 */
    fun confirmImport() {
        val plan = _state.value.plan ?: return
        if (plan.hosts.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                val identities = _state.value.identities
                var keyCount = 0
                var hostCount = 0
                withContext(Dispatchers.IO) {
                    // 私钥：按别名建占位条目，后续主机用 keyId 引用
                    val aliasToId = HashMap<String, Long>()
                    for (draft in plan.keys) {
                        val pem = identities.entries.firstOrNull { (name, _) ->
                            draft.sourcePath.replace('\\', '/').trimEnd('/').endsWith(name) ||
                                name == draft.sourcePath
                        }?.value ?: identities.values.firstOrNull { content ->
                            SshConfigImportPlanner.guessKeyType(content) == draft.keyType
                        }
                        if (pem == null) continue
                        val id = keyRepo.import(
                            alias = draft.alias,
                            privateKeyPem = pem,
                            sourceName = draft.sourcePath
                        )
                        aliasToId[draft.alias] = id
                        keyCount++
                    }
                    var order = 0
                    for (draft in plan.hosts) {
                        val keyId = draft.keyAlias?.let { aliasToId[it] }
                        hostRepo.save(
                            SshConfigImportPlanner.toHostEntity(
                                draft,
                                keyId,
                                sortOrder = order++,
                                noneAuthWhenNoKey = _state.value.noneAuthWhenNoKey
                            ),
                            plainPassword = null
                        )
                        hostCount++
                    }
                }
                _state.value = _state.value.copy(
                    busy = false,
                    identities = emptyMap(),
                    doneMessage = "已导入 $hostCount 台主机" +
                        if (keyCount > 0) "、$keyCount 个私钥" else ""
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = "导入失败：${e.message}")
            }
        }
    }

    // ===== 工具 =====

    private fun readUri(uri: Uri): Pair<String, String> {
        val resolver = getApplication<Application>().contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment ?: "config"
        val text = resolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: error("无法打开文件")
        return name to text
    }

    /** 配置里多个条目共用 `User` 时，取第一个出现的作为默认值，减少用户填写 */
    private fun defaultUserGuess(hosts: List<SshConfigParser.ParsedHost>): String =
        hosts.firstNotNullOfOrNull { it.user?.takeIf { u -> u.isNotBlank() } } ?: ""

    init {
        // 加密主密钥兜底（正常由 AsshApp.onCreate 保证，这里防异常路径）
        runCatching { CryptoManager.ensureKey(getApplication()) }
    }
}
