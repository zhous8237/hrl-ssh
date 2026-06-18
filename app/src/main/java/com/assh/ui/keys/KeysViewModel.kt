package com.assh.ui.keys

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.assh.AsshApp
import com.assh.data.db.entity.CredentialEntity
import com.assh.data.db.entity.KeyEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class KeysViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as AsshApp).keyRepository
    private val credRepo = (app as AsshApp).credentialRepository

    val keys: StateFlow<List<KeyEntity>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val credentials: StateFlow<List<CredentialEntity>> = credRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    // ===== 私钥 =====

    fun import(alias: String, pem: String, sourceName: String?, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                repo.import(alias = alias, privateKeyPem = pem, sourceName = sourceName)
                onDone()
            } catch (e: Exception) {
                _message.value = e.message ?: "导入失败"
            }
        }
    }

    /** 编辑私钥；pem 留空 = 保持原值；sourceName 非空 = 重新选了文件 */
    fun update(
        key: KeyEntity,
        alias: String,
        pem: String,
        sourceName: String?,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            try {
                repo.update(
                    key = key,
                    alias = alias,
                    privateKeyPem = pem.takeIf { it.isNotBlank() },
                    sourceName = sourceName
                )
                onDone()
            } catch (e: Exception) {
                _message.value = e.message ?: "保存失败"
            }
        }
    }

    fun delete(key: KeyEntity) {
        viewModelScope.launch {
            val ok = repo.delete(key)
            if (!ok) _message.value = "「${key.alias}」正被服务器配置引用，无法删除"
        }
    }

    // ===== 密码凭据（功能 4）=====

    fun importCredential(alias: String, password: String, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                credRepo.import(alias = alias, password = password)
                onDone()
            } catch (e: Exception) {
                _message.value = e.message ?: "保存失败"
            }
        }
    }

    /** 编辑密码；password 留空 = 保持原值 */
    fun updateCredential(
        credential: CredentialEntity,
        alias: String,
        password: String,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            try {
                credRepo.update(
                    credential = credential,
                    alias = alias,
                    password = password.takeIf { it.isNotBlank() }
                )
                onDone()
            } catch (e: Exception) {
                _message.value = e.message ?: "保存失败"
            }
        }
    }

    fun deleteCredential(credential: CredentialEntity) {
        viewModelScope.launch {
            val ok = credRepo.delete(credential)
            if (!ok) _message.value = "「${credential.alias}」正被服务器配置引用，无法删除"
        }
    }

    fun clearMessage() { _message.value = null }
}
