package com.assh.ui.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.assh.AsshApp
import com.assh.ai.AgentHistoryStore
import com.assh.ai.AgentPreferences
import com.assh.ai.AgentRunRecord
import com.assh.ai.AgentState
import com.assh.ai.ConfirmPolicy
import com.assh.ai.llm.ChatMessage
import com.assh.ai.llm.LlmConfig
import com.assh.ai.llm.LlmProfile
import com.assh.ai.llm.LlmProvider
import com.assh.data.db.entity.HostEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** AI 运维 Agent 的 ViewModel：任务会话 + 多配置管理 + 历史。 */
class AgentViewModel(app: Application) : AndroidViewModel(app) {

    private val asshApp = app as AsshApp
    private val prefs: AgentPreferences = asshApp.agentPreferences
    private val engine = asshApp.sshAgentEngine
    private val historyStore: AgentHistoryStore = asshApp.agentHistoryStore

    val state: StateFlow<AgentState> = engine.state

    val hosts: StateFlow<List<HostEntity>> =
        asshApp.hostRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val profiles: StateFlow<List<LlmProfile>> =
        prefs.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeProfileId: StateFlow<String?> =
        prefs.activeProfileId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val confirmPolicy: StateFlow<ConfirmPolicy> =
        prefs.confirmPolicy.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConfirmPolicy.DANGEROUS_ONLY)

    val history: StateFlow<List<AgentRunRecord>> =
        historyStore.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _settingsMessage = MutableStateFlow<String?>(null)
    val settingsMessage: StateFlow<String?> = _settingsMessage.asStateFlow()

    private val _settingsBusy = MutableStateFlow(false)
    val settingsBusy: StateFlow<Boolean> = _settingsBusy.asStateFlow()

    private val _models = MutableStateFlow<List<String>>(emptyList())
    val models: StateFlow<List<String>> = _models.asStateFlow()

    private val _fetchingModels = MutableStateFlow(false)
    val fetchingModels: StateFlow<Boolean> = _fetchingModels.asStateFlow()

    // —— 任务会话 ——
    fun start(hostId: Long, goal: String) = engine.start(hostId, goal)
    fun continueTask(text: String) = engine.continueTask(text)
    fun resumeSession(record: AgentRunRecord) = engine.resume(record)
    fun stopTurn() = engine.cancel()
    fun removeTimelineItem(index: Int) = engine.removeTimelineItem(index)
    fun endSession() = engine.endSession()
    fun approve() = engine.approve()
    fun reject() = engine.reject()
    fun trustHostKey() = engine.trustHostKey()
    fun rejectHostKey() = engine.rejectHostKey()
    fun consumeFinished() = engine.consumeFinishedMessage()

    // —— 多配置 ——
    fun setActiveProfile(id: String) = viewModelScope.launch { prefs.setActive(id) }
    fun setConfirmPolicy(policy: ConfirmPolicy) = viewModelScope.launch { prefs.setConfirmPolicy(policy) }
    fun deleteProfile(id: String) = viewModelScope.launch { prefs.deleteProfile(id) }
    fun saveProfile(profile: LlmProfile, plainKey: String?) {
        viewModelScope.launch {
            prefs.upsertProfile(profile, plainKey)
            _settingsMessage.value = "已保存"
        }
    }

    fun clearModels() { _models.value = emptyList() }
    fun consumeSettingsMessage() { _settingsMessage.value = null }

    /** 用编辑表单的值测试连接（key 留空则复用被编辑 profile 的原密钥） */
    fun testConnection(provider: LlmProvider, baseUrl: String, model: String, plainKey: String?, editingId: String?) {
        viewModelScope.launch {
            _settingsBusy.value = true
            try {
                val cfg = buildConfig(provider, baseUrl, model, plainKey, editingId)
                if (!cfg.isUsable) { _settingsMessage.value = "请填写地址、模型和 API Key"; return@launch }
                val resp = asshApp.llmClientFactory.forConfig(cfg).chat(
                    system = "你是连接测试助手。",
                    messages = listOf(ChatMessage.user("请只回复两个字：正常")),
                    tools = emptyList(),
                    config = cfg
                )
                _settingsMessage.value = "连接成功（${cfg.model}）：" + (resp.assistantText?.take(30) ?: "已响应")
            } catch (e: Exception) {
                _settingsMessage.value = "连接失败：${e.message}"
            } finally {
                _settingsBusy.value = false
            }
        }
    }

    fun fetchModels(provider: LlmProvider, baseUrl: String, model: String, plainKey: String?, editingId: String?) {
        viewModelScope.launch {
            _fetchingModels.value = true
            try {
                val cfg = buildConfig(provider, baseUrl, model, plainKey, editingId)
                if (cfg.baseUrl.isBlank() || cfg.apiKey.isBlank()) {
                    _settingsMessage.value = "请先填写接口地址和 API Key"; return@launch
                }
                val list = asshApp.llmClientFactory.forConfig(cfg).listModels(cfg)
                _models.value = list
                _settingsMessage.value = if (list.isEmpty()) "未获取到模型，可手动填写模型名"
                    else "已获取 ${list.size} 个模型，点模型框选择"
            } catch (e: Exception) {
                _settingsMessage.value = "获取模型失败：${e.message}"
            } finally {
                _fetchingModels.value = false
            }
        }
    }

    private suspend fun buildConfig(provider: LlmProvider, baseUrl: String, model: String, plainKey: String?, editingId: String?): LlmConfig {
        val key = plainKey ?: editingId?.let { prefs.decryptKey(it) } ?: ""
        return LlmConfig(provider, baseUrl.trim(), key, model.trim())
    }

    // —— 历史 ——
    fun deleteHistory(id: String) = viewModelScope.launch { historyStore.delete(id) }
    fun clearHistory() = viewModelScope.launch { historyStore.clear() }
}
