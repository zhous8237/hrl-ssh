package com.assh.ssh

import android.util.Log
import com.assh.FileLog
import com.assh.data.db.dao.KnownHostDao
import com.assh.data.db.entity.AuthType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.DisconnectListener
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.io.InputStream
import java.io.OutputStream

enum class ConnState { IDLE, CONNECTING, CONNECTED, DISCONNECTED, ERROR }

/**
 * 单个 SSH 连接（文档 §7.1）：持有一个 sshj SSHClient + 交互式 Shell（PTY）。
 * 实现 [SshTransport] seam（C2），生产路径由 [SshjTransportFactory] 构造。
 */
class SshSession(
    override val hostId: Long,
    private val knownHostDao: KnownHostDao
) : SshTransport {
    private companion object { const val TAG = "assh-ssh" }

    /** 同时写 logcat 和文件日志（部分机型 IDE 抓不到 logcat） */
    private fun flog(msg: String) {
        android.util.Log.i(TAG, msg)
        FileLog.log(TAG, msg)
    }

    private var client: SSHClient? = null
    private var session: Session? = null
    private var shell: Session.Shell? = null

    /**
     * 出站操作单线程执行器：resize / write 都是网络 I/O，绝不能在 UI 主线程跑
     * （否则抛 NetworkOnMainThreadException，破坏 sshj transport，连接随即 EOF）。
     * 单线程同时保证写入顺序。
     */
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

    /** resize 防抖：软键盘动画/双指缩放期间每帧都会触发 onResize，逐条发包既慢又挤占写队列 */
    private var resizeFuture: java.util.concurrent.ScheduledFuture<*>? = null

    /**
     * 已关闭标记：close() 会 shutdown ioExecutor，此后任何 execute/schedule 都会抛
     * RejectedExecutionException（"...rejected from ...[Terminated]"）。close 可能被多次调用
     * （读线程 EOF→cleanupResources→transport.close→close，再叠加 UI 的断开/重连），
     * 故 close 必须幂等，且关闭后所有出站操作直接丢弃，绝不再投递到死线程池。
     */
    @Volatile
    private var closed = false

    override lateinit var stdout: InputStream
        private set
    override lateinit var stdin: OutputStream
        private set

    private val _state = MutableStateFlow(ConnState.IDLE)
    override val state = _state.asStateFlow()

    /** 错误信息，state == ERROR 时供 UI 展示 */
    @Volatile
    override var lastError: String? = null
        private set

    /** 用户主动断开标记，区分意外断线（自动重连判据） */
    @Volatile
    var userClosed = false
        private set

    /** 服务器主动断开时由 DisconnectListener 填入的真实原因；EOF 断开时用作 lastError */
    @Volatile
    private var disconnectReason: String? = null

    override suspend fun connect(cfg: ResolvedHostConfig, cols: Int, rows: Int) =
        withContext(Dispatchers.IO) {
            _state.value = ConnState.CONNECTING
            try {
                val config = DefaultConfig().apply {
                    keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
                }
                val c = SSHClient(config)
                client = c
                c.addHostKeyVerifier(AsshHostKeyVerifier(knownHostDao))
                c.connectTimeout = 15_000
                c.timeout = 0                       // 交互会话不超时
                c.connection.keepAlive.keepAliveInterval = 30  // 心跳检测半开连接
                // 传输层断开监听：捕获服务器主动断开的真实原因（如 sshd 限制、超时、被踢）
                c.transport.setDisconnectListener(DisconnectListener { reason, message ->
                    flog("disconnectListener: reason=$reason message=$message")
                    if (disconnectReason == null) {
                        disconnectReason = buildString {
                            append("服务器断开连接")
                            append("（$reason）")
                            if (message.isNotBlank()) append("：$message")
                        }
                    }
                })
                flog("connect: tcp connecting ${cfg.host}:${cfg.port}")
                c.connect(cfg.host, cfg.port)
                flog("connect: tcp connected, authenticating as ${cfg.username} (${cfg.authType})")

                when (cfg.authType) {
                    AuthType.PASSWORD -> {
                        val password = cfg.password
                            ?: throw IllegalStateException("密码未提供")
                        c.authPassword(cfg.username, password)
                    }
                    AuthType.KEY -> {
                        val pem = cfg.privateKeyPem
                            ?: throw IllegalStateException("私钥未提供")
                        c.authPublickey(cfg.username, loadKeyProvider(c, pem))
                    }
                }
                flog("connect: auth ok, starting session")

                val s = c.startSession()
                session = s
                flog("connect: session started, allocating PTY")
                s.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap())
                flog("connect: PTY allocated, starting shell")
                val sh = s.startShell()
                shell = sh
                // stdout 包一层缓冲：减少对 sshj ChannelInputStream 的 read 次数/锁竞争，
                // 高吞吐输出（cat 大文件、编译日志）更顺；不会延迟单字符回显（fill 只读"至少 1 字节"）
                stdout = java.io.BufferedInputStream(sh.inputStream, 32 * 1024)
                stdin = sh.outputStream
                _state.value = ConnState.CONNECTED
                flog("connect: shell started, state=CONNECTED")
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                _state.value = ConnState.ERROR
                flog("connect: failed - ${e.javaClass.name}: ${e.message} " + e.stackTraceToString())
                runCatching { client?.disconnect() }
                throw e
            }
        }

    private fun loadKeyProvider(c: SSHClient, pem: String): KeyProvider {
        return c.loadKeys(pem, null, null)
    }

    override fun resize(cols: Int, rows: Int) {
        if (closed) return
        // 防抖 120ms：键盘弹出动画/捏合缩放会连发十几次 resize，只有最终尺寸有意义。
        // 中间值逐条发到服务器会让远端 TUI（vim/htop）反复重排，表现为缩放“延时感”。
        resizeFuture?.cancel(false)
        try {
            resizeFuture = ioExecutor.schedule({
                runCatching { shell?.changeWindowDimensions(cols, rows, 0, 0) }
                    .onFailure { flog("resize: failed - ${it.javaClass.name}: ${it.message}") }
            }, 120, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // 与 close() 竞态：池刚 shutdown。静默丢弃。
        }
    }

    /** 读线程 EOF / IOException 时调用 */
    override fun markDisconnected() {
        flog("markDisconnected: state=${_state.value} disconnectReason=$disconnectReason")
        if (_state.value == ConnState.CONNECTED) {
            // 优先用服务器返回的断开原因；否则给通用提示，避免“莫名断开”
            if (lastError == null) {
                lastError = disconnectReason
                    ?: "连接已断开：服务器关闭了会话（可能是登录 shell 立即退出、sshd 会话限制或网络中断）。"
            }
            _state.value = ConnState.DISCONNECTED
        }
    }

    @Synchronized
    override fun close(byUser: Boolean) {
        if (byUser) userClosed = true
        // 幂等：close 可能被多次调用（EOF 路径 + UI 断开/重连路径），第二次直接返回，
        // 否则会向已 shutdown 的 ioExecutor 投递任务，抛 RejectedExecutionException
        // （崩溃/重连报错根因）。
        if (closed) return
        closed = true

        if (_state.value != ConnState.ERROR) {
            _state.value = ConnState.DISCONNECTED
        }
        // 关闭也是网络 IO（发 SSH_MSG_CHANNEL_CLOSE / DISCONNECT），不能在主线程跑，
        // 否则 NetworkOnMainThreadException 被吞、socket 关不干净
        try {
            ioExecutor.execute {
                runCatching { shell?.close() }
                runCatching { session?.close() }
                runCatching { client?.disconnect() }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // 池已被关停（理论上 closed 守卫已挡住，这里兜底），直接同步清理
            runCatching { shell?.close() }
            runCatching { session?.close() }
            runCatching { client?.disconnect() }
        }
        ioExecutor.shutdown()   // 已入队任务会执行完；之后该会话不再收发
    }
}

/** 生产工厂：用真实 sshj 支撑的 [SshSession] 满足 [SshTransport] seam */
class SshjTransportFactory(
    private val knownHostDao: KnownHostDao
) : SshTransportFactory {
    override fun create(hostId: Long): SshTransport = SshSession(hostId, knownHostDao)
}
