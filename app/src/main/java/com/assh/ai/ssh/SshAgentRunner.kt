package com.assh.ai.ssh

import com.assh.data.db.dao.KnownHostDao
import com.assh.data.db.entity.AuthType
import com.assh.ssh.AsshHostKeyVerifier
import com.assh.ssh.ResolvedHostConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.keepalive.KeepAliveRunner
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AI Agent 专用的 SSH 执行器：独立 [SSHClient]，只用**非交互 exec channel**
 * （`session.exec(cmd)`）。相比交互式 PTY shell（[com.assh.ssh.SshSession]），
 * exec 的 stdout/stderr 分离、有干净退出码，AI 能可靠判断命令成败。
 *
 * 独立连接 → 不干扰用户正在用的终端会话。复用 [AsshHostKeyVerifier] 走同一 known_hosts 校验。
 * 所有方法在 IO 线程执行，调用方应在协程中调用。
 */
class SshAgentRunner(private val knownHostDao: KnownHostDao) {

    @Volatile
    private var client: SSHClient? = null

    /** 最近一次连接配置：连接被判死时据此透明重连（AI 对话期间不应让用户看到"连接已断开"） */
    @Volatile
    private var lastCfg: ResolvedHostConfig? = null

    val isConnected: Boolean get() = client?.isConnected == true

    suspend fun connect(cfg: ResolvedHostConfig) = withContext(Dispatchers.IO) {
        lastCfg = cfg
        // keepAlive 必须开：AI 每步之间要调 LLM（可能几十秒、含退避重试），其间 SSH 连接空闲，
        // 不发心跳会被服务器/NAT 断开，下一条命令就报 not connected / 卡死在读流。
        val config = DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
        val c = SSHClient(config)
        c.addHostKeyVerifier(AsshHostKeyVerifier(knownHostDao))
        c.connectTimeout = 15_000
        c.timeout = 0
        c.connect(cfg.host, cfg.port)
        c.connection.keepAlive.keepAliveInterval = 20
        // 默认 maxAliveCount=5：5×20s=100s 没收到心跳回包就自杀式断开。抓 GitHub 页 + 喂大段内容
        // 给慢/被限流模型的间隙很容易超过它。调高到 30（≈600s）避免长间隙被自身 keepalive 误杀。
        (c.connection.keepAlive as? KeepAliveRunner)?.maxAliveCount = 30
        when (cfg.authType) {
            AuthType.PASSWORD ->
                c.authPassword(cfg.username, cfg.password ?: throw IllegalStateException("密码未提供"))
            AuthType.KEY ->
                c.authPublickey(cfg.username, c.loadKeys(cfg.privateKeyPem ?: throw IllegalStateException("私钥未提供"), null, null))
        }
        client = c
    }

    /** 连接已断则用 [lastCfg] 透明重连；已连接直接返回。无重连信息则抛出。 */
    suspend fun ensureConnected() = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext
        val cfg = lastCfg ?: throw IllegalStateException("SSH 连接已断开，且无可用的重连信息")
        runCatching { client?.disconnect() }
        client = null
        connect(cfg)
    }

    /**
     * 执行一条命令并返回结果。stdout/stderr 并发读以防管道写满死锁；
     * 超过 [maxOutputBytes] 截断（仍继续 drain 丢弃，避免阻塞服务器）；
     * [timeoutSec] 内未结束则打断并置 [ExecResult.timedOut]。
     */
    suspend fun runCommand(
        command: String,
        timeoutSec: Int = 120,
        maxOutputBytes: Int = 16 * 1024,
        onPartialStdout: ((String) -> Unit)? = null
    ): ExecResult = withContext(Dispatchers.IO) {
        ensureConnected()   // 连接被判死则透明重连，避免单步因空闲掉线而失败
        val c = client?.takeIf { it.isConnected } ?: throw IllegalStateException("SSH 连接已断开")
        val start = System.currentTimeMillis()
        val session = c.startSession()
        var timedOut = false
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
                timedOut = true   // join 超时（或连接中断）
            }
            tOut.join(2_000); tErr.join(2_000)
            val exit = runCatching { cmd.exitStatus }.getOrNull()
            ExecResult(
                stdout = out.toString().trimEnd(),
                stderr = err.toString().trimEnd(),
                exitStatus = exit,
                truncated = outTrunc.get() || errTrunc.get(),
                timedOut = timedOut,
                durationMs = System.currentTimeMillis() - start
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
}
