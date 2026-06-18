package com.assh.ssh

import com.assh.data.db.dao.KnownHostDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 多连接管理 + 重连（文档 §7.4）。
 * 进程内单例，由前台 Service 间接持有；缓存最近一次成功连接的
 * ResolvedHostConfig，支持一键重连（无需重新解密/输密码）。
 *
 * 后台保活（功能 6）：App 退到后台不立即断开，由 ProcessLifecycle 调用
 * onAppBackgrounded / onAppForegrounded 控制一个 10 分钟延时断开计时器。
 *
 * C2 seam：会话经 [SshTransportFactory] 创建（生产为 sshj，测试为内存 fake），
 * [scope] 可注入以便测试用 TestScope 驱动状态聚合。
 */
class SshConnectionManager(
    private val transportFactory: SshTransportFactory,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    /** 便利构造：生产路径用 sshj 工厂 */
    constructor(knownHostDao: KnownHostDao) : this(SshjTransportFactory(knownHostDao))

    private val sessions = ConcurrentHashMap<Long, SshTransport>()

    /** 重连用的配置缓存（含明文凭据，进程级内存，不落盘） */
    private val configCache = ConcurrentHashMap<Long, ResolvedHostConfig>()

    private var backgroundTimerJob: Job? = null

    /** 各主机连接状态聚合流（主机列表实时刷新用）。无连接的主机不在 map 中，视为 IDLE。 */
    private val _states = MutableStateFlow<Map<Long, ConnState>>(emptyMap())
    val states: StateFlow<Map<Long, ConnState>> = _states.asStateFlow()

    /** 收集单个 session 状态变化的 Job，断开时取消 */
    private val watchJobs = ConcurrentHashMap<Long, Job>()

    private fun publishState(hostId: Long, state: ConnState) {
        _states.value = _states.value.toMutableMap().apply { put(hostId, state) }
    }

    private fun removeState(hostId: Long) {
        _states.value = _states.value.toMutableMap().apply { remove(hostId) }
    }

    /** 订阅 session 的状态流，实时同步到聚合 map */
    private fun watch(hostId: Long, session: SshTransport) {
        watchJobs.remove(hostId)?.cancel()
        watchJobs[hostId] = scope.launch {
            session.state.collect { publishState(hostId, it) }
        }
    }

    /** App 退到后台：启动 10 分钟延时断开计时；超时未回前台则全部断开 */
    fun onAppBackgrounded(graceMillis: Long = 10 * 60 * 1000L) {
        backgroundTimerJob?.cancel()
        if (activeCount == 0) return
        backgroundTimerJob = scope.launch {
            delay(graceMillis)
            disconnectAll()
        }
    }

    /** App 回到前台：取消待执行的断开计时 */
    fun onAppForegrounded() {
        backgroundTimerJob?.cancel()
        backgroundTimerJob = null
    }

    fun get(hostId: Long): SshTransport? = sessions[hostId]

    fun cachedConfig(hostId: Long): ResolvedHostConfig? = configCache[hostId]

    val activeCount: Int
        get() = sessions.values.count { it.state.value == ConnState.CONNECTED }

    suspend fun connect(cfg: ResolvedHostConfig): SshTransport {
        sessions[cfg.hostId]?.takeIf { it.state.value == ConnState.CONNECTED }?.let { return it }
        val s = transportFactory.create(cfg.hostId)
        sessions[cfg.hostId] = s
        watch(cfg.hostId, s)
        s.connect(cfg)
        configCache[cfg.hostId] = cfg
        return s
    }

    /** 一键重连（功能 6）：复用缓存配置 */
    suspend fun reconnect(hostId: Long): SshTransport {
        val cfg = configCache[hostId]
            ?: throw IllegalStateException("无缓存配置，无法重连")
        sessions[hostId]?.close()
        sessions.remove(hostId)
        return connect(cfg)
    }

    fun disconnect(hostId: Long) {
        sessions.remove(hostId)?.close(byUser = true)
        configCache.remove(hostId)
        watchJobs.remove(hostId)?.cancel()
        removeState(hostId)
    }

    /**
     * 意外断线清理（非用户主动断开）：回收会让进程"关不掉"的残留——
     * 移除僵尸 session、取消状态订阅协程、复位聚合状态。
     *
     * 与 [disconnect] 的区别：
     * - 不重复 close()：会话已由 TerminalSession.cleanupResources → ssh.close() 关闭；
     * - 不设 userClosed：这是意外断线；
     * - **保留 configCache**：一键重连（功能 6）仍可免重新解密/输密码。
     */
    fun cleanupDisconnected(hostId: Long) {
        sessions.remove(hostId)
        watchJobs.remove(hostId)?.cancel()
        removeState(hostId)
    }

    fun disconnectAll() {
        sessions.keys.toList().forEach { disconnect(it) }
    }
}
