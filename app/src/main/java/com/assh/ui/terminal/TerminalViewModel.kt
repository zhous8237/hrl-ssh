package com.assh.ui.terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.assh.AsshApp
import com.assh.data.db.entity.CommandEntity
import com.assh.data.db.entity.HostEntity
import com.assh.service.SshForegroundService
import com.assh.ssh.ConnState
import com.assh.ssh.HostKeyChangedException
import com.assh.terminal.AsshTerminalSessionClient
import com.assh.terminal.SshSessionTransport
import com.assh.ui.theme.GreenSuccess
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 终端屏 UI 状态 */
data class TerminalUiState(
    val host: HostEntity? = null,
    val connState: ConnState = ConnState.IDLE,
    val error: String? = null,
    /** 需要用户输入密码（配置未存密码） */
    val needPassword: Boolean = false,
    /** HostKey 变更详情，非 null 时弹警告 */
    val hostKeyChanged: HostKeyChangedException? = null,
    /** 粘滞键状态 */
    val ctrlActive: Boolean = false,
    val altActive: Boolean = false,
    /** 连接成功的瞬时提示（2 秒即逝通知条） */
    val connectedToast: Boolean = false
)

class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    private val asshApp = app as AsshApp
    private val hostRepo = asshApp.hostRepository
    private val manager = asshApp.connectionManager
    private val registry = asshApp.terminalRegistry

    private val _ui = MutableStateFlow(TerminalUiState())
    val ui = _ui.asStateFlow()

    /**
     * 终端会话；Compose 里 AndroidView attach 用。
     * 必须是可观察状态：AndroidView 的 update 块靠它从 null → 实际会话的变化触发重组并 attach，
     * 否则连接成功后终端不渲染（黑屏）。
     */
    private val _termSession = MutableStateFlow<TerminalSession?>(null)
    val termSessionFlow = _termSession.asStateFlow()
    var termSession: TerminalSession?
        get() = _termSession.value
        private set(value) { _termSession.value = value }

    /**
     * 初始命令暂存：TerminalSession 的 IO 线程要等 TerminalView 首次 updateSize
     * （initializeEmulator）才启动，提前 write 会被丢弃。由 onEmulatorReady() 消费。
     */
    private var pendingInitialCommand: String? = null

    /**
     * TerminalView 重绘回调：由 UI 层注册，输出回调（TerminalSession 主线程 Handler）
     * 直接驱动 view.onScreenUpdated()。不能走 StateFlow→重组→LaunchedEffect，
     * 那条链每个输出块多 2~3 帧延迟且全屏重组（输入回显/缩放卡顿根因）。
     */
    var onScreenUpdated: (() -> Unit)? = null

    /** 复制回调：TerminalView 选中文本 → 系统剪贴板（UI 层处理） */
    val copyRequest = MutableStateFlow<String?>(null)

    var commands: StateFlow<List<CommandEntity>> =
        MutableStateFlow<List<CommandEntity>>(emptyList())
        private set

    private var hostId: Long = -1
    private var stateWatchJob: Job? = null

    fun init(hostId: Long) {
        if (this.hostId == hostId) return
        this.hostId = hostId

        commands = asshApp.commandRepository.observeForHost(hostId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        viewModelScope.launch {
            val host = hostRepo.findById(hostId)
            _ui.value = _ui.value.copy(host = host)

            // 已有活跃会话（从列表重进）→ 复用
            val existing = manager.get(hostId)
            val existingTerm = registry.get(hostId)
            if (existing != null && existing.state.value == ConnState.CONNECTED && existingTerm != null) {
                // 回调必须重绑到本 VM：旧 VM 已随上次退出销毁（onScreenUpdated 已置 null），
                // 不重绑则重进后输出不再触发重绘，终端画面冻结
                existingTerm.updateTerminalSessionClient(makeSessionClient(existing))
                termSession = existingTerm
                watchState()
                _ui.value = _ui.value.copy(connState = ConnState.CONNECTED)
                return@launch
            }
            connect(null)
        }
    }

    /** 构建绑定到当前 VM 的会话回调；新建与复用 TerminalSession 都必须经此绑定 */
    private fun makeSessionClient(ssh: com.assh.ssh.SshTransport) = AsshTerminalSessionClient(
        onTextChangedHook = { onScreenUpdated?.invoke() },
        onSessionFinishedHook = {
            ssh.markDisconnected()
            _ui.value = _ui.value.copy(connState = ConnState.DISCONNECTED, error = ssh.lastError)
            // 意外断线（服务器关会话 / shell 退出 / 网络中断）也必须回收资源：
            // 移除僵尸 session、取消状态订阅，并在无活跃连接时停掉前台 Service。
            // 否则前台通知钉住进程，划掉 App 后进程仍不被系统回收（"关 App 后不恢复"）。
            // configCache 由 Dropped 拆链故意保留，一键重连仍免密。
            manager.cleanupDisconnected(hostId)
            stopServiceIfNoActiveConnections()
        },
        onCopyToClipboardHook = { copyRequest.value = it },
        onPasteFromClipboardHook = { /* UI 层工具条处理粘贴 */ }
    )

    /** @param inputPassword 配置未存密码时由弹窗提供 */
    fun connect(inputPassword: String?) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                connState = ConnState.CONNECTING,
                error = null, needPassword = false, hostKeyChanged = null
            )
            try {
                var cfg = hostRepo.resolveForConnect(hostId)
                if (cfg.authType == com.assh.data.db.entity.AuthType.PASSWORD && cfg.password == null) {
                    if (inputPassword == null) {
                        _ui.value = _ui.value.copy(connState = ConnState.IDLE, needPassword = true)
                        return@launch
                    }
                    cfg = cfg.copy(password = inputPassword)
                }

                val ssh = manager.connect(cfg)
                hostRepo.touchLastConnected(hostId)
                SshForegroundService.start(getApplication())

                // 建终端会话：SSH 流 → SessionTransport → TerminalSession
                val term = TerminalSession(SshSessionTransport(ssh), 2000, makeSessionClient(ssh))
                termSession = term
                registry.put(hostId, term)

                watchState()
                _ui.value = _ui.value.copy(connState = ConnState.CONNECTED, connectedToast = true)

                // 初始命令（功能 1 高级设置）：等 emulator 就绪后由 onEmulatorReady 下发
                pendingInitialCommand = cfg.initialCommand
            } catch (e: HostKeyChangedException) {
                _ui.value = _ui.value.copy(connState = ConnState.ERROR, hostKeyChanged = e)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    connState = ConnState.ERROR,
                    error = e.message ?: e.javaClass.simpleName
                )
            }
        }
    }

    private fun watchState() {
        stateWatchJob?.cancel()
        val ssh = manager.get(hostId) ?: return
        stateWatchJob = viewModelScope.launch {
            ssh.state.collect { s ->
                if (s == ConnState.DISCONNECTED || s == ConnState.ERROR) {
                    _ui.value = _ui.value.copy(connState = s, error = ssh.lastError)
                }
            }
        }
    }

    /** 一键重连（功能 6） */
    fun reconnect() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(connState = ConnState.CONNECTING, error = null)
            try {
                registry.remove(hostId)?.finishIfRunning()
                val ssh = manager.reconnect(hostId)
                val term = TerminalSession(SshSessionTransport(ssh), 2000, makeSessionClient(ssh))
                termSession = term
                registry.put(hostId, term)
                watchState()
                _ui.value = _ui.value.copy(connState = ConnState.CONNECTED, connectedToast = true)
            } catch (e: Exception) {
                // 无缓存配置（如进程被杀后重启）→ 走完整连接流程
                if (e is IllegalStateException) {
                    connect(null)
                } else {
                    _ui.value = _ui.value.copy(connState = ConnState.ERROR, error = e.message)
                }
            }
        }
    }

    /** HostKey 变更：用户确认信任 → 更新指纹后重连 */
    fun trustNewHostKey() {
        val ex = _ui.value.hostKeyChanged ?: return
        viewModelScope.launch {
            asshApp.database.knownHostDao().delete(ex.hostPort)
            _ui.value = _ui.value.copy(hostKeyChanged = null)
            connect(null)
        }
    }

    fun dismissHostKeyDialog() {
        _ui.value = _ui.value.copy(hostKeyChanged = null, connState = ConnState.ERROR, error = "已取消连接（host key 未信任）")
    }

    fun consumeConnectedToast() {
        _ui.value = _ui.value.copy(connectedToast = false)
    }

    /** TerminalView 首次 attach 完成（emulator 就绪、IO 线程已启动）时回调 */
    fun onEmulatorReady() {
        pendingInitialCommand?.let {
            termSession?.write(it + "\r")
            pendingInitialCommand = null
        }
    }

    // ===== 输入收口：所有命令下发唯一经 termSession.write()（v1 §15 地基）=====

    /** 自定义命令 Chip 点击 */
    fun sendCommand(cmd: CommandEntity) {
        termSession?.write(cmd.command + if (cmd.appendEnter) "\r" else "")
    }

    /** 粘贴 */
    fun paste(text: String) {
        termSession?.write(text)
    }

    /** 工具条文本键（如 / - | ~） */
    fun sendText(text: String) {
        termSession?.write(applyStickyToChar(text))
    }

    /** 原样下发（不经粘滞变换）：用于工具条上预设的 Ctrl 组合键（^C/^D/^Z 等） */
    fun sendRaw(text: String) {
        termSession?.write(text)
    }

    /** Ctrl/Alt 粘滞键切换 */
    fun toggleCtrl() { _ui.value = _ui.value.copy(ctrlActive = !_ui.value.ctrlActive) }
    fun toggleAlt() { _ui.value = _ui.value.copy(altActive = !_ui.value.altActive) }

    /** 粘滞组合：委托纯函数 [KeyEncoder]（C7，逻辑已抽出以便单测并与工具条 ^X 去重） */
    private fun applyStickyToChar(text: String): String {
        val s = _ui.value
        val out = KeyEncoder.applySticky(text, s.ctrlActive, s.altActive)
        if (s.ctrlActive || s.altActive) {
            _ui.value = s.copy(ctrlActive = false, altActive = false)
        }
        return out
    }

    /** 读取并消费粘滞状态（TerminalView 硬件键路径用） */
    fun consumeSticky(): Pair<Boolean, Boolean> {
        val s = _ui.value
        if (s.ctrlActive || s.altActive) {
            _ui.value = s.copy(ctrlActive = false, altActive = false)
        }
        return s.ctrlActive to s.altActive
    }

    fun disconnect() {
        registry.remove(hostId)?.finishIfRunning()
        manager.disconnect(hostId)
        stopServiceIfNoActiveConnections()
    }

    /** 无活跃连接时停掉前台 Service（C5：原本在 disconnect 与意外断线回调里各写一份） */
    private fun stopServiceIfNoActiveConnections() {
        if (manager.activeCount == 0) {
            SshForegroundService.stop(getApplication())
        }
    }

    /** 新增自定义命令（终端屏「+」） */
    fun saveCommand(cmd: CommandEntity) {
        viewModelScope.launch { asshApp.commandRepository.save(cmd) }
    }
}
