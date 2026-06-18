package com.assh.ui.hosts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.assh.AsshApp
import com.assh.data.db.entity.AuthType
import com.assh.data.db.entity.CredentialEntity
import com.assh.data.db.entity.HostEntity
import com.assh.data.db.entity.KeyEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HostEditState(
    val label: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    val password: String = "",          // 仅当用户输入时非空；编辑模式留空 = 不修改
    val passwordTouched: Boolean = false,
    val hasStoredPassword: Boolean = false,
    val credentialId: Long? = null,     // 引用独立密码库；非空时忽略内联 password（功能 4）
    val keyId: Long? = null,
    val charset: String = "UTF-8",
    val initialCommand: String = "",
    val saving: Boolean = false,
    val error: String? = null
) {
    val valid: Boolean
        get() = label.isNotBlank() && host.isNotBlank() && username.isNotBlank() &&
            (port.toIntOrNull() in 1..65535) &&
            (authType == AuthType.PASSWORD || keyId != null)
}

/**
 * 主机地址标准化（功能 3）：支持域名 / IPv4 / IPv6。
 * IPv6 无需用户输入 []，粘贴带 [] 的（如 "[::1]"）也自动剥离；统一裸地址存储。
 */
internal fun normalizeHost(raw: String): String {
    var h = raw.trim()
    if (h.startsWith("[") && h.endsWith("]")) {
        h = h.substring(1, h.length - 1).trim()
    }
    return h
}

class HostEditViewModel(app: Application) : AndroidViewModel(app) {

    private val asshApp = app as AsshApp
    private val hostRepo = asshApp.hostRepository

    private val _state = MutableStateFlow(HostEditState())
    val state = _state.asStateFlow()

    val keys: StateFlow<List<KeyEntity>> = asshApp.keyRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val credentials: StateFlow<List<CredentialEntity>> = asshApp.credentialRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var editingId: Long? = null

    fun load(hostId: Long?) {
        if (hostId == null || editingId == hostId) return
        editingId = hostId
        viewModelScope.launch {
            hostRepo.findById(hostId)?.let { h ->
                _state.value = HostEditState(
                    label = h.label,
                    host = h.host,
                    port = h.port.toString(),
                    username = h.username,
                    authType = h.authType,
                    hasStoredPassword = h.encPassword != null,
                    credentialId = h.credentialId,
                    keyId = h.keyId,
                    charset = h.charset,
                    initialCommand = h.initialCommand.orEmpty()
                )
            }
        }
    }

    fun update(transform: HostEditState.() -> HostEditState) {
        _state.value = _state.value.transform()
    }

    fun save(onSaved: () -> Unit) {
        val s = _state.value
        if (!s.valid || s.saving) return
        _state.value = s.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                val existing = editingId?.let { hostRepo.findById(it) }
                // 密码认证且选了密码库别名 → 用引用，不存内联密码
                val useCredential = s.authType == AuthType.PASSWORD && s.credentialId != null
                val entity = HostEntity(
                    id = existing?.id ?: 0,
                    label = s.label.trim(),
                    host = normalizeHost(s.host),
                    port = s.port.toInt(),
                    username = s.username.trim(),
                    authType = s.authType,
                    encPassword = if (useCredential) null else existing?.encPassword,  // 默认保持，repo 按 plainPassword 决定
                    credentialId = if (useCredential) s.credentialId else null,
                    keyId = if (s.authType == AuthType.KEY) s.keyId else null,
                    charset = s.charset.trim().ifEmpty { "UTF-8" },
                    initialCommand = s.initialCommand.trim().ifEmpty { null },
                    sortOrder = existing?.sortOrder ?: 0,
                    lastConnectedAt = existing?.lastConnectedAt
                )
                // 密码语义：用密码库引用 = 清空内联密码；否则 未触碰 = null（不修改）；触碰后为空 = 清除；非空 = 重新加密
                val plainPassword = when {
                    s.authType != AuthType.PASSWORD -> ""   // 切换到私钥认证则清掉密码
                    useCredential -> ""                     // 用密码库引用，清掉内联密码
                    !s.passwordTouched -> null
                    else -> s.password
                }
                hostRepo.save(entity, plainPassword)
                onSaved()
            } catch (e: Exception) {
                _state.value = _state.value.copy(saving = false, error = e.message)
            }
        }
    }
}
