package com.assh.ai.ssh

import com.assh.data.db.dao.KnownHostDao
import com.assh.ssh.ResolvedHostConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 异常 cause 链（限深防环）是否含 [java.util.concurrent.TimeoutException]——sshj 真超时的标志（区别于断线）。 */
internal fun hasTimeoutCause(e: Throwable): Boolean =
    generateSequence<Throwable>(e) { it.cause }.take(16)
        .any { it is java.util.concurrent.TimeoutException }

/**
 * AI Agent 专用的 SSH 执行器：独立连接，只用**非交互 exec channel**
 * （`session.exec(cmd)`）。相比交互式 PTY shell（[com.assh.ssh.SshSession]），
 * exec 的 stdout/stderr 分离、有干净退出码，AI 能可靠判断命令成败。
 *
 * 独立连接 → 不干扰用户正在用的终端会话。建连/认证经 [AgentSshClientFactory]（默认
 * [SshjAgentClientFactory] 复用同一 known_hosts 校验），便于退避重连逻辑脱离真服务器单测。
 * 所有方法在 IO 线程执行，调用方应在协程中调用。
 */
class SshAgentRunner(
    knownHostDao: KnownHostDao,
    private val factory: AgentSshClientFactory = SshjAgentClientFactory(knownHostDao),
    private val backoff: Backoff = Backoff(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    @Volatile
    private var client: AgentSshClient? = null

    /** 最近一次连接配置：连接被判死时据此透明重连（AI 对话期间不应让用户看到"连接已断开"） */
    @Volatile
    private var lastCfg: ResolvedHostConfig? = null

    val isConnected: Boolean get() = client?.isConnected == true

    suspend fun connect(cfg: ResolvedHostConfig) {
        lastCfg = cfg
        client = factory.connect(cfg)
    }

    /**
     * 连接已断则退避重连，最多耗 [budgetMs]；成功 true。
     * 致命错（认证/密钥/算法/指纹，见 [SshErrorClassifier]）立即抛出、不耗预算；
     * 可重连错（传输/网络）按 [backoff] 指数退避+抖动重试直到预算耗尽返回 false。
     * 退避期间 `delay` 可被任务取消打断（[CancellationException] 透传，供「继续」抢占）。
     */
    private suspend fun reconnectWithBackoff(budgetMs: Long): Boolean = withContext(ioDispatcher) {
        val cfg = lastCfg ?: throw SshReconnectFailedException("无可用的重连信息")
        // withTimeoutOrNull 走协程 delay 计时（受测试虚拟时钟支配，便于单测）；预算内未连上
        // 返回 null→false；致命错（非超时取消）照常向外抛、不耗尽预算。
        val ok = withTimeoutOrNull(budgetMs) {
            var attempt = 0
            var connected = false
            while (!connected) {
                ensureActive()
                runCatching { client?.disconnect() }
                client = null
                connected = try {
                    client = factory.connect(cfg); true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (SshErrorClassifier.classify(e) == SshErrorKind.FATAL) throw e
                    false
                }
                if (!connected) delay(backoff.delayMs(attempt++))
            }
            true
        }
        ok ?: false
    }

    /** 连接已断则退避重连（[RECONNECT_BUDGET_MS] 预算）；连着直接返回；重连失败抛 [SshReconnectFailedException]。 */
    suspend fun ensureConnected() {
        if (isConnected) return
        if (!reconnectWithBackoff(RECONNECT_BUDGET_MS)) throw SshReconnectFailedException()
    }

    /**
     * 后台慢重试用：尝试**一次**重连（无内部退避循环，由调用方按 ~20–30s 间隔驱动）。
     * 成功 true；瞬时失败 false；致命错（认证/密钥/指纹）抛出，交调用方转硬失败。
     */
    suspend fun tryReconnect(): Boolean = withContext(ioDispatcher) {
        if (isConnected) return@withContext true
        val cfg = lastCfg ?: return@withContext false
        runCatching { client?.disconnect() }
        client = null
        try {
            client = factory.connect(cfg); true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (SshErrorClassifier.classify(e) == SshErrorKind.FATAL) throw e
            false
        }
    }

    /**
     * 执行一条命令并返回结果。stdout/stderr 并发读以防管道写满死锁；
     * 超过 [maxOutputBytes] 截断（仍继续 drain 丢弃，避免阻塞服务器）；
     * [timeoutSec] 内未结束则打断并置 [ExecResult.timedOut]。
     *
     * 断线处理（区分于超时）：
     * - **开始前断**：[ensureConnected] 退避重连；预算耗尽抛 [SshReconnectFailedException]（不返回假结果）。
     * - **半途断**：置 [ExecResult.interrupted]，原地退避重连（成功置 [ExecResult.reconnected]），
     *   **不重跑命令**——交模型据部分输出决定（杜绝改状态命令被重复执行）。
     */
    suspend fun runCommand(
        command: String,
        timeoutSec: Int = 120,
        maxOutputBytes: Int = 16 * 1024,
        onPartialStdout: ((String) -> Unit)? = null
    ): ExecResult = withContext(ioDispatcher) {
        ensureConnected()   // 开始前断→退避重连；预算耗尽抛 SshReconnectFailedException
        val c = client?.takeIf { it.isConnected } ?: throw SshReconnectFailedException()
        val start = System.currentTimeMillis()
        var timedOut = false
        var interrupted = false
        var reconnected = false
        val session = c.startSession()
        try {
            val cmd = session.exec(command)
            val out = StringBuilder()
            val err = StringBuilder()
            val outTrunc = AtomicBoolean(false)
            val errTrunc = AtomicBoolean(false)
            val tOut = Thread { drainInto(cmd.inputStream, out, maxOutputBytes, outTrunc, onPartialStdout) }
            val tErr = Thread { drainInto(cmd.errorStream, err, maxOutputBytes, errTrunc) }
            tOut.start(); tErr.start()
            try {
                cmd.join(timeoutSec.toLong(), TimeUnit.SECONDS)
            } catch (e: Exception) {
                // 区分真超时 vs 执行中断线：sshj 纯超时的 cause 链含 TimeoutException；
                // 断线是 transport 死亡（deliverError），cause 链无 TimeoutException 且连接已掉。
                if (hasTimeoutCause(e) && c.isConnected) timedOut = true else interrupted = true
            }
            tOut.join(2_000); tErr.join(2_000)
            val exit = runCatching { cmd.exitStatus }.getOrNull()
            if (interrupted) {
                // 半途断：原地退避重连（不重跑命令），成功则连接可继续，余下由模型决定
                reconnected = runCatching { reconnectWithBackoff(RECONNECT_BUDGET_MS) }.getOrDefault(false)
            }
            ExecResult(
                stdout = out.toString().trimEnd(),
                stderr = err.toString().trimEnd(),
                exitStatus = exit,
                truncated = outTrunc.get() || errTrunc.get(),
                timedOut = timedOut,
                durationMs = System.currentTimeMillis() - start,
                interrupted = interrupted,
                reconnected = reconnected
            )
        } finally {
            runCatching { session.close() }
        }
    }

    /** 一次性只读探测，给模型初始上下文（发行版 / 包管理器 / 用户 / 目录） */
    suspend fun systemProbe(): String {
        val probe = "echo \"OS: \$(uname -srm 2>/dev/null)\"; " +
            "echo \"DISTRO: \$(. /etc/os-release 2>/dev/null && echo \$PRETTY_NAME)\"; " +
            "echo \"USER: \$(whoami) (uid=\$(id -u))\"; " +
            "echo \"PWD: \$(pwd)\"; " +
            "echo \"PKG: \$(command -v apt-get dnf yum apk pacman zypper 2>/dev/null | tr '\\n' ' ')\""
        return runCatching { runCommand(probe, timeoutSec = 20).stdout }
            .getOrElse { "（系统探测失败：${it.message}）" }
    }

    fun close() {
        runCatching { client?.disconnect() }
        client = null
    }

    private fun drainInto(
        ins: InputStream,
        sb: StringBuilder,
        max: Int,
        trunc: AtomicBoolean,
        onUpdate: ((String) -> Unit)? = null
    ) {
        try {
            val buf = ByteArray(4096)
            var kept = 0
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                if (kept < max) {
                    val take = minOf(n, max - kept)
                    sb.append(String(buf, 0, take, Charsets.UTF_8))
                    kept += take
                    if (kept >= max) trunc.set(true)
                    onUpdate?.invoke(sb.toString())   // 实时回调当前累积（调用方负责节流）
                }
                // 超过上限后继续读并丢弃，避免服务器端管道阻塞
            }
        } catch (_: Exception) {
            // 流被关闭（超时打断 / 连接断开）→ 正常收尾
        }
    }

    companion object {
        /** 命令开始前断线 / 半途断线后，原地阻塞退避重连的预算（覆盖一次普通 reboot ≈30–90s） */
        private const val RECONNECT_BUDGET_MS = 90_000L
    }
}
