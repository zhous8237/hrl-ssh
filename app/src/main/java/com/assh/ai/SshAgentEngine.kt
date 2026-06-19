package com.assh.ai

import android.content.Context
import com.assh.ai.llm.ChatMessage
import com.assh.ai.llm.LlmClient
import com.assh.ai.llm.LlmClientFactory
import com.assh.ai.llm.LlmConfig
import com.assh.ai.llm.LlmException
import com.assh.ai.llm.Role
import com.assh.ai.llm.StopReason
import com.assh.ai.ssh.ExecResult
import com.assh.ai.ssh.SshAgentRunner
import com.assh.ai.ssh.SshErrorClassifier
import com.assh.ai.ssh.SshErrorKind
import com.assh.ai.ssh.SshReconnectFailedException
import com.assh.ai.tools.ShellOutcome
import com.assh.ai.tools.ToolContext
import com.assh.ai.tools.ToolOutcome
import com.assh.data.db.dao.KnownHostDao
import com.assh.data.repo.HostRepository
import com.assh.service.AgentForegroundService
import com.assh.ssh.HostKeyChangedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * AI 运维 Agent 引擎：编排 tool-use 循环，支持**多轮继续对话**。**进程级单例**（挂 AsshApp），
 * 任务跑在自有 [scope]（脱离 UI 生命周期），配合 [AgentForegroundService] 前台保活——
 * App 退后台 / 页面销毁后仍继续执行。
 *
 * 会话生命周期：[start] 建连接并跑首轮 → 每轮处理完进入 AWAITING_FOLLOWUP **保持连接**，
 * 用户可 [continueTask] 追加指令接着干；闲置 [IDLE_TIMEOUT_MS] 无新指令自动 [endSession] 释放。
 * 每轮结束把会话快照写入 [historyStore]。
 */
class SshAgentEngine(
    private val appContext: Context,
    private val hostRepository: HostRepository,
    private val agentPreferences: AgentPreferences,
    private val llmClientFactory: LlmClientFactory,
    private val knownHostDao: KnownHostDao,
    private val historyStore: AgentHistoryStore
) {
    companion object {
        private const val MAX_STEPS_PER_TURN = 25

        /** 一轮结束后，闲置超过此时长（无追加指令）自动结束会话、释放 SSH 连接 */
        private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val webFetcher = WebFetcher()
    private val webSearcher = WebSearcher()

    /**
     * 工具运行时上下文：把需要触碰引擎状态的能力（确认 / 时间线 / 部分输出 / SSH 执行）
     * 实现在引擎内，[com.assh.ai.tools.Tool] 实现保持薄而可测。
     */
    private val toolContext = object : ToolContext {
        override fun notice(text: String) = addItem(TimelineItem.Notice(text))

        override suspend fun runShell(command: String, why: String?, timeoutSec: Int): ShellOutcome {
            if (!confirmIfNeeded(command, why)) return ShellOutcome.Rejected
            setPhase(AgentPhase.EXECUTING, _state.value.step)
            addItem(TimelineItem.Command(command, why, CmdStatus.RUNNING))
            var lastPartialMs = 0L
            val res = runner?.runCommand(command, timeoutSec, onPartialStdout = { partial ->
                // 节流约 300ms：长命令（apt/编译/下载）执行时实时把输出刷到时间线，消除"执行中"干等
                val now = System.currentTimeMillis()
                if (now - lastPartialMs >= 300) {
                    lastPartialMs = now
                    updateRunningCommandPartial(partial)
                }
            }) ?: return ShellOutcome.ConnectionClosed
            updateLastRunningCommand(res)
            // 半途断线且未能原地重连 → 抛出转入"已断开·重连中"可恢复态（不直接报错结束）；
            // 若已重连（interrupted && reconnected）则照常 Done，把中断结果交模型决定是否重做。
            if (res.interrupted && !res.reconnected) throw SshReconnectFailedException()
            return ShellOutcome.Done(res)
        }

        override suspend fun runDetachedJob(innerCommand: String, why: String?, wrappedCommand: String): ShellOutcome {
            if (!confirmIfNeeded(innerCommand, why)) return ShellOutcome.Rejected
            // 时间线展示内层命令（用户能懂），实际执行 setsid 包装串；包装串后台化、瞬时返回
            setPhase(AgentPhase.EXECUTING, _state.value.step)
            addItem(TimelineItem.Command(innerCommand, why, CmdStatus.RUNNING))
            val res = runner?.runCommand(wrappedCommand, timeoutSec = 30) ?: return ShellOutcome.ConnectionClosed
            updateLastRunningCommand(res)
            if (res.interrupted && !res.reconnected) throw SshReconnectFailedException()
            return ShellOutcome.Done(res)
        }

        override suspend fun runReadonly(command: String, timeoutSec: Int): ShellOutcome {
            val res = runner?.runCommand(command, timeoutSec) ?: return ShellOutcome.ConnectionClosed
            if (res.interrupted && !res.reconnected) throw SshReconnectFailedException()
            return ShellOutcome.Done(res)
        }

        override suspend fun fetchUrl(url: String): String = webFetcher.fetch(url)
        override suspend fun search(query: String): String = webSearcher.search(query)
    }

    private val _state = MutableStateFlow(AgentState())
    val state = _state.asStateFlow()

    private var job: Job? = null
    private var idleJob: Job? = null
    private var reconnectJob: Job? = null
    private var confirmDeferred: CompletableDeferred<Boolean>? = null
    private var hostKeyDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var runner: SshAgentRunner? = null

    // —— 会话级状态 ——
    private val messages = mutableListOf<ChatMessage>()
    private var llm: LlmClient? = null
    private var llmConfig: LlmConfig? = null
    private var systemPrompt = ""
    private var sessionId = ""
    private var sessionTitle = ""
    private var sessionHostLabel = ""
    private var sessionHostId = -1L
    private var sessionStartedAt = 0L
    private var sessionSuccess = false

    /** 开始一个新会话 */
    fun start(hostId: Long, goal: String) {
        if (_state.value.sessionActive) return
        cancelIdleTimer()
        val g = goal.trim()
        sessionId = "s" + System.currentTimeMillis()
        sessionTitle = g.ifBlank { "（空目标）" }
        sessionHostLabel = ""
        sessionHostId = hostId
        sessionStartedAt = System.currentTimeMillis()
        sessionSuccess = false
        messages.clear()
        _state.value = AgentState(phase = AgentPhase.CONNECTING)
        startKeepAlive()
        job = scope.launch { setupAndRun(hostId, g) }
    }

    /** 在当前会话上追加指令继续；text 为空则重新执行原任务。断线态下即「立即重连」入口 */
    fun continueTask(text: String) {
        if (_state.value.phase != AgentPhase.AWAITING_FOLLOWUP) return
        val t = text.trim().ifBlank { sessionTitle }
        if (t.isBlank()) return
        cancelIdleTimer()
        reconnectJob?.cancel(); reconnectJob = null   // 接管后台重连，避免双路并发
        setPhase(AgentPhase.THINKING)
        job = scope.launch {
            // 连接若已断（长时间空闲/网络抖动）先透明重连（含退避）；失败转回"已断开"态继续后台重连，而非报错
            if (!ensureRunnerConnected()) { enterDisconnected("立即重连未成功"); return@launch }
            _state.update { it.copy(disconnected = false) }   // 手动重连成功，清断线标志
            // 重新解析当前选中的模型配置——用户可在上方切换模型后再点「继续」（含模型报错后换模型重试）
            if (!refreshLlm()) return@launch
            // 连接与模型都就绪后再落消息，避免检查失败时留下悬空 / 重复的用户消息
            messages.add(ChatMessage.user(t))
            addItem(TimelineItem.UserText(t))
            runTurns()
        }
    }

    /** 隐藏一条时间线记录（仅非执行中可用）；不影响其余逻辑 */
    fun removeTimelineItem(index: Int) {
        if (_state.value.running) return
        _state.update { st ->
            if (index in st.timeline.indices)
                st.copy(timeline = st.timeline.filterIndexed { i, _ -> i != index })
            else st
        }
    }

    /** 从历史记录恢复会话：重连服务器、回填对话上下文与时间线，进入可继续状态 */
    fun resume(record: AgentRunRecord) {
        if (_state.value.sessionActive) return
        cancelIdleTimer()
        if (record.hostId < 0) {
            _state.update { it.copy(phase = AgentPhase.ERROR, finishedMessage = "该记录缺少主机信息，无法继续") }
            return
        }
        sessionId = record.id
        sessionTitle = record.title
        sessionHostLabel = record.hostLabel
        sessionHostId = record.hostId
        sessionStartedAt = record.startedAt
        sessionSuccess = record.success
        messages.clear()
        messages.addAll(record.messages.map { it.toChatMessage() })
        _state.value = AgentState(phase = AgentPhase.CONNECTING, timeline = record.entries.map { it.toTimelineItem() })
        startKeepAlive()
        job = scope.launch { resumeConnect(record.hostId) }
    }

    private suspend fun resumeConnect(hostId: Long) {
        val config = agentPreferences.resolveActiveConfig()
        if (config == null) { fail("请先在「AI 助手」设置里添加并选中一个模型配置"); return }
        llmConfig = config
        llm = llmClientFactory.forConfig(config)
        val r = SshAgentRunner(knownHostDao)
        runner = r
        try {
            val cfg = hostRepository.resolveForConnect(hostId)
            sessionHostLabel = cfg.label
            connectTrustingHostKey { r.connect(cfg) }
            pruneOldJobLogs()
            systemPrompt = AgentTools.systemPrompt(r.systemProbe())
            addItem(TimelineItem.Notice("已重新连接 ${cfg.label}，可继续对话"))
            _state.update { it.copy(phase = AgentPhase.AWAITING_FOLLOWUP, finishedMessage = "已恢复会话，可追加指令继续") }
            startIdleTimer()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(readableError(e))
        }
    }

    fun approve() { confirmDeferred?.complete(true) }
    fun reject() { confirmDeferred?.complete(false) }

    /** host key 变更确认（UI 弹窗回调）：信任→删旧指纹后重连续跑；拒绝→连接失败 */
    fun trustHostKey() { hostKeyDeferred?.complete(true) }
    fun rejectHostKey() { hostKeyDeferred?.complete(false) }

    /** 停止本轮 AI 处理，但保持连接（仍可继续）；连接已断则彻底结束 */
    fun cancel() {
        confirmDeferred?.complete(false)
        job?.cancel()
        reconnectJob?.cancel(); reconnectJob = null
        if (runner?.isConnected == true) {
            saveHistory("已停止")
            _state.update { it.copy(phase = AgentPhase.AWAITING_FOLLOWUP, pendingConfirm = null, finishedMessage = "已停止本轮，可追加指令或结束会话") }
            startIdleTimer()
        } else {
            closeRunner(); stopKeepAlive(); saveHistory("已取消")
            _state.update { it.copy(phase = AgentPhase.CANCELLED, pendingConfirm = null, finishedMessage = "已取消") }
        }
    }

    /** 结束会话：关连接、停保活、清空当前记录（历史已落库） */
    fun endSession() = endSession("会话已结束")

    private fun endSession(message: String) {
        cancelIdleTimer()
        reconnectJob?.cancel(); reconnectJob = null
        job?.cancel()
        confirmDeferred?.complete(false)
        closeRunner(); stopKeepAlive(); saveHistory("已结束")
        _state.value = AgentState(phase = AgentPhase.DONE, finishedMessage = message)
    }

    fun consumeFinishedMessage() {
        _state.update { it.copy(finishedMessage = null) }
    }

    private suspend fun setupAndRun(hostId: Long, goal: String) {
        val config = agentPreferences.resolveActiveConfig()
        if (config == null) { fail("请先在「AI 助手」设置里添加并选中一个模型配置"); return }
        if (goal.isBlank()) { fail("请先描述要完成的目标"); return }
        llmConfig = config
        llm = llmClientFactory.forConfig(config)
        val r = SshAgentRunner(knownHostDao)
        runner = r
        try {
            val cfg = hostRepository.resolveForConnect(hostId)
            sessionHostLabel = cfg.label
            connectTrustingHostKey { r.connect(cfg) }
            pruneOldJobLogs()
            setPhase(AgentPhase.THINKING)
            systemPrompt = AgentTools.systemPrompt(r.systemProbe())
            messages.add(ChatMessage.user(goal))
            runTurns()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(readableError(e))   // 初次建连/探测失败：直接失败（连不上就别进重连 limbo，首轮还没跑）
        }
    }

    /** 跑到本轮结束（finish / 步数上限 / 模型不再调工具），结束后保持连接等待追加 */
    private suspend fun runTurns() {
        val config = llmConfig ?: return
        val client = llm ?: return
        try {
            var step = 0
            while (step < MAX_STEPS_PER_TURN) {
                coroutineContext.ensureActive()
                step++
                setPhase(AgentPhase.THINKING, step)

                val resp = client.chat(systemPrompt, messages, AgentTools.registry.specs, config)
                messages.add(resp.rawAssistantMessage)
                if (!resp.assistantText.isNullOrBlank()) addItem(TimelineItem.AiText(resp.assistantText))

                if (resp.toolCalls.isEmpty()) {
                    if (resp.stopReason == StopReason.LENGTH) {
                        addItem(TimelineItem.Notice("⚠ 模型输出可能因长度上限被截断，结论或不完整。可点「继续」让它补全。"))
                    }
                    awaitFollowup(true, resp.assistantText ?: "（本轮结束）"); return
                }

                for (call in resp.toolCalls) {
                    coroutineContext.ensureActive()
                    val tool = AgentTools.registry.get(call.name)
                    if (tool == null) {
                        messages.add(ChatMessage.tool(call.id, "未知工具：${call.name}")); continue
                    }
                    when (val outcome = tool.execute(call.argumentsJson, toolContext)) {
                        is ToolOutcome.Finish -> { awaitFollowup(outcome.success, outcome.summary); return }
                        is ToolOutcome.Continue -> messages.add(ChatMessage.tool(call.id, outcome.toolResult))
                    }
                }
            }
            awaitFollowup(false, "已达本轮步数上限（$MAX_STEPS_PER_TURN 步）。可追加指令继续或结束会话。")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleTurnError(e)
        }
    }

    /** 本轮结束：保持连接，进入等待追加状态，落历史并启动闲置计时 */
    private fun awaitFollowup(success: Boolean, summary: String) {
        sessionSuccess = success
        // 模型常把结论既写进 assistantText 又放进 finish.summary，重复时不再追加一条
        val last = _state.value.timeline.lastOrNull()
        val alreadyShown = last is TimelineItem.AiText && last.text.trim() == summary.trim()
        if (summary.isNotBlank() && !alreadyShown) addItem(TimelineItem.AiText(summary))
        _state.update {
            it.copy(phase = AgentPhase.AWAITING_FOLLOWUP, pendingConfirm = null, success = success, finishedMessage = summary)
        }
        saveHistory("已完成")
        startIdleTimer()
    }

    private suspend fun awaitConfirm(): Boolean {
        val d = CompletableDeferred<Boolean>()
        confirmDeferred = d
        return try { d.await() } finally { confirmDeferred = null }
    }

    /** 危险检测 + 按策略弹确认。返回 true=可执行，false=被用户拒绝（调用方应返回 Rejected）。 */
    private suspend fun confirmIfNeeded(command: String, why: String?): Boolean {
        val cls = DangerousCommandDetector.classify(command)
        val needConfirm = when (agentPreferences.confirmPolicyValue()) {
            ConfirmPolicy.ALWAYS -> true
            ConfirmPolicy.DANGEROUS_ONLY -> cls.level == RiskLevel.DANGEROUS
            ConfirmPolicy.NEVER -> false
        }
        if (!needConfirm) return true
        _state.update { it.copy(phase = AgentPhase.WAITING_CONFIRM, pendingConfirm = PendingConfirm(command, why, cls)) }
        val approved = awaitConfirm()
        _state.update { it.copy(pendingConfirm = null) }
        if (!approved) {
            addItem(TimelineItem.Notice("已拒绝执行：$command"))
            return false
        }
        return true
    }

    /**
     * 执行建连动作；若因 host key 变更（TOFU 比对失败）抛 [HostKeyChangedException]，
     * 暂停等用户决策：同意则删旧指纹后重连（下次 verify 走首次信任路径记录新指纹），
     * 拒绝则抛出交上层失败处理。与终端路径 [com.assh.ui.terminal.TerminalViewModel.trustNewHostKey] 行为对齐。
     */
    private suspend fun connectTrustingHostKey(connectAction: suspend () -> Unit) {
        while (true) {
            try {
                connectAction()
                return
            } catch (e: HostKeyChangedException) {
                val approved = awaitHostKeyConfirm(e)
                if (!approved) throw IllegalStateException("已取消连接：未信任服务器的新指纹")
                knownHostDao.delete(e.hostPort)
                // 回到循环重连：旧指纹已删，verify 将以首次信任写入新指纹
            }
        }
    }

    private suspend fun awaitHostKeyConfirm(e: HostKeyChangedException): Boolean {
        val d = CompletableDeferred<Boolean>()
        hostKeyDeferred = d
        _state.update {
            it.copy(
                phase = AgentPhase.WAITING_HOSTKEY,
                pendingHostKey = PendingHostKey(e.hostPort, e.savedFingerprint, e.actualFingerprint)
            )
        }
        addItem(TimelineItem.Notice("⚠ 服务器 ${e.hostPort} 的 host key 已变更，等待确认是否信任新指纹"))
        return try {
            d.await()
        } finally {
            hostKeyDeferred = null
            _state.update { it.copy(pendingHostKey = null) }
        }
    }

    private fun cancelIdleTimer() { idleJob?.cancel(); idleJob = null }

    /** 进入"可追加"等待态后启动闲置计时：[IDLE_TIMEOUT_MS] 内无追加指令则自动结束、释放连接 */
    private fun startIdleTimer() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            // 仍在等待追加（用户既没继续也没结束）才自动释放；其余状态不动
            if (_state.value.phase == AgentPhase.AWAITING_FOLLOWUP) {
                endSession("闲置超过 10 分钟，已自动断开连接")
            }
        }
    }

    private fun startKeepAlive() = runCatching { AgentForegroundService.start(appContext) }
    private fun stopKeepAlive() = runCatching { AgentForegroundService.stop(appContext) }

    private fun closeRunner() {
        runCatching { runner?.close() }
        runner = null
    }

    /** 连接后清理 24h 前的旧后台任务日志（best-effort，不影响主流程、不污染探测输出） */
    private fun pruneOldJobLogs() {
        val r = runner ?: return
        scope.launch {
            runCatching { r.runCommand("find \$HOME/.assh/jobs -type f -mtime +1 -delete 2>/dev/null", timeoutSec = 15) }
        }
    }

    private fun saveHistory(phaseLabel: String) {
        if (sessionId.isBlank()) return
        val record = AgentRunRecord(
            id = sessionId,
            hostLabel = sessionHostLabel,
            title = sessionTitle,
            startedAt = sessionStartedAt,
            updatedAt = System.currentTimeMillis(),
            success = sessionSuccess,
            phaseLabel = phaseLabel,
            entries = _state.value.timeline.map { it.toRecordEntry() },
            hostId = sessionHostId,
            messages = messages.map { it.toStored() }
        )
        scope.launch { runCatching { historyStore.upsert(record) } }
    }

    // —— 状态更新辅助 ——

    private fun setPhase(phase: AgentPhase, step: Int = _state.value.step) {
        _state.update { it.copy(phase = phase, step = step) }
    }

    private fun addItem(item: TimelineItem) {
        _state.update { it.copy(timeline = it.timeline + item) }
    }

    /** 实时把执行中命令的部分 stdout 刷到时间线（仅取尾部，避免超长字符串拖累重组） */
    private fun updateRunningCommandPartial(partial: String) {
        _state.update { st ->
            val idx = st.timeline.indexOfLast { it is TimelineItem.Command && it.status == CmdStatus.RUNNING }
            if (idx < 0) return@update st
            val old = st.timeline[idx] as TimelineItem.Command
            val newList = st.timeline.toMutableList()
            newList[idx] = old.copy(partialOutput = partial.takeLast(2000))
            st.copy(timeline = newList)
        }
    }

    private fun updateLastRunningCommand(res: ExecResult) {
        _state.update { st ->
            val idx = st.timeline.indexOfLast { it is TimelineItem.Command && it.status == CmdStatus.RUNNING }
            if (idx < 0) return@update st
            val old = st.timeline[idx] as TimelineItem.Command
            val newList = st.timeline.toMutableList()
            newList[idx] = old.copy(status = CmdStatus.DONE, result = res)
            st.copy(timeline = newList)
        }
    }

    private fun fail(message: String) {
        cancelIdleTimer()
        reconnectJob?.cancel(); reconnectJob = null
        addItem(TimelineItem.Notice("错误：$message"))
        closeRunner(); stopKeepAlive()
        _state.update { it.copy(phase = AgentPhase.ERROR, pendingConfirm = null, success = false, finishedMessage = message) }
        saveHistory("出错")
    }

    /**
     * 本轮出错的统一处理：
     * - 模型错误 [LlmException] → 软失败保连接续命（可换模型「继续」重试）。
     * - 致命 SSH 错（认证/密钥/算法/host key 变更，见 [SshErrorClassifier]）→ [fail] 彻底停下，需用户处理。
     * - 传输类断开且连接已掉（含 [SshReconnectFailedException]）→ [enterDisconnected] 后台重连，**不进 ERROR**。
     * - 其余（连接还在的瞬时错）→ 软失败可续。
     */
    private fun handleTurnError(e: Throwable) {
        when {
            e is LlmException -> failSoft(readableError(e))
            e is HostKeyChangedException -> fail(readableError(e))
            SshErrorClassifier.classify(e) == SshErrorKind.FATAL -> fail(readableError(e))
            runner?.isConnected != true -> enterDisconnected(readableError(e))
            else -> failSoft(readableError(e))
        }
    }
    /**
     * 软失败：**不关连接、不停保活**。模型报错 / 限流重试用尽时，保留 SSH 与对话上下文，
     * 转入"可追加"等待态——用户可在上方切换模型后点「继续」重试（问题 2），并启动闲置计时。
     */
    private fun failSoft(message: String) {
        reconcileDanglingToolCalls()
        addItem(TimelineItem.Notice("模型出错：$message"))
        _state.update {
            it.copy(
                phase = AgentPhase.AWAITING_FOLLOWUP, pendingConfirm = null, success = false,
                finishedMessage = "模型出错：$message。可在上方切换模型后点「继续」重试。"
            )
        }
        saveHistory("模型出错(可继续)")
        startIdleTimer()
    }

    /**
     * 进入"已断开·重连中"可恢复态（仿 [failSoft]，但额外起后台限时重连）：
     * **不关连接、不停保活**，保留对话上下文；phase 仍为 AWAITING_FOLLOWUP（自动可「继续」）。
     * 区别于 [fail]（进 ERROR、关连接），传输类断开走这里——再不会出现"会话不能继续"。
     */
    private fun enterDisconnected(message: String) {
        reconcileDanglingToolCalls()
        cancelIdleTimer()
        addItem(TimelineItem.Notice("连接已断开（$message），正在后台重连，也可点「继续」立即重连。"))
        _state.update {
            it.copy(
                phase = AgentPhase.AWAITING_FOLLOWUP, disconnected = true, pendingConfirm = null,
                success = false, finishedMessage = "连接已断开，正在后台重连。可点「继续」立即重连。"
            )
        }
        saveHistory("已断开(重连中)")
        startReconnectJob(System.currentTimeMillis() + IDLE_TIMEOUT_MS)
    }

    /**
     * 后台慢重连：每 ~20–30s（带抖动）试一次，连上即恢复；[deadlineAtMs]（掉线起 [IDLE_TIMEOUT_MS]）
     * 仍未连上则按闲置释放。致命错（认证/密钥/指纹）转 [fail]。「继续」会取消本 job 自行接管。
     */
    private fun startReconnectJob(deadlineAtMs: Long) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            try {
                while (System.currentTimeMillis() < deadlineAtMs) {
                    if (!_state.value.disconnected) return@launch   // 已被「继续」接管
                    val ok = try {
                        runner?.tryReconnect() == true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        fail(readableError(e)); return@launch       // 致命错：后台重试无意义
                    }
                    if (ok) { onBackgroundReconnected(); return@launch }
                    delay(20_000L + Random.nextLong(0, 10_001))     // 20–30s 慢重试，带抖动
                }
                if (_state.value.disconnected) endSession("断线超过 10 分钟，已自动结束会话")
            } catch (_: CancellationException) {
                // 被「继续」/结束接管，正常退出
            }
        }
    }

    private fun onBackgroundReconnected() {
        addItem(TimelineItem.Notice("已重连，可继续对话"))
        _state.update {
            it.copy(phase = AgentPhase.AWAITING_FOLLOWUP, disconnected = false, finishedMessage = "已重连，可继续对话")
        }
        saveHistory("已重连")
        startIdleTimer()
    }

    /**
     * 补全悬空 tool_call：若最后一条 assistant 消息带 tool_calls 但部分缺对应 tool 结果
     * （执行中途出错时可能发生），补占位结果，避免下一轮请求因 tool_call 未配对而被拒。
     */
    private fun reconcileDanglingToolCalls() {
        val idx = messages.indexOfLast { it.role == Role.ASSISTANT }
        if (idx < 0) return
        val assistant = messages[idx]
        if (assistant.toolCalls.isEmpty()) return
        val answered = messages.drop(idx + 1)
            .filter { it.role == Role.TOOL }
            .mapNotNull { it.toolCallId }
            .toSet()
        for (tc in assistant.toolCalls) {
            if (tc.id !in answered) {
                messages.add(ChatMessage.tool(tc.id, "（上一步未完成：执行被中断或出错，未取得结果）"))
            }
        }
    }

    /** 重新解析当前选中模型并重建客户端；无可用配置则软失败提示。供「继续」时切模型生效。 */
    private suspend fun refreshLlm(): Boolean {
        val config = agentPreferences.resolveActiveConfig()
        if (config == null) { failSoft("当前没有可用的模型配置，请先在设置里选择一个"); return false }
        llmConfig = config
        llm = llmClientFactory.forConfig(config)
        return true
    }

    /** 确保 runner 已连接：断了用 lastCfg 透明重连。成功（连着）返回 true。 */
    private suspend fun ensureRunnerConnected(): Boolean {
        val r = runner ?: return false
        if (r.isConnected) return true
        return runCatching { r.ensureConnected(); r.isConnected }.getOrDefault(false)
    }

    private fun readableError(e: Throwable): String = when (e) {
        is HostKeyChangedException -> "服务器 host key 已变更（可能是重装或中间人攻击）。请结束本会话后重新开始，以确认新指纹。"
        is LlmException -> e.message ?: "AI 调用失败"
        is IllegalArgumentException -> e.message ?: "参数错误"
        is IllegalStateException -> e.message ?: "状态错误"
        else -> e.message ?: "执行失败：${e.javaClass.simpleName}"
    }
}
