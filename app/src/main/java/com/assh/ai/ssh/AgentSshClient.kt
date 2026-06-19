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
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.IOException

/**
 * AI exec 路径的连接抽象（A0 seam）：把建连/认证/会话开启隔离到接口后，
 * [SshAgentRunner] 的退避重连逻辑可注入 fake、脱离真服务器单测。
 *
 * 注意：与交互式终端的 [com.assh.ssh.SshTransport] 是两套——后者面向 PTY shell
 * （stdin/stdout 流、resize、状态聚合），这里面向非交互 exec channel，互不复用。
 */
interface AgentSshClient {
    val isConnected: Boolean
    fun startSession(): Session
    fun disconnect()
}

/**
 * 建立一个**已认证、已开 keepalive** 的连接；失败抛异常（由 [SshErrorClassifier] 分类
 * 决定可重连还是致命）。实现应在 IO 线程执行阻塞建连。
 */
fun interface AgentSshClientFactory {
    suspend fun connect(cfg: ResolvedHostConfig): AgentSshClient
}

/** 重连在预算内仍未恢复（可重连类——[SshErrorClassifier] 判 TRANSIENT）。 */
class SshReconnectFailedException(
    message: String = "连接已断开，且在重连预算内未能恢复"
) : IOException(message)

/** 生产实现：包一个真 sshj [SSHClient]。 */
private class SshjAgentClient(private val ssh: SSHClient) : AgentSshClient {
    override val isConnected: Boolean get() = ssh.isConnected
    override fun startSession(): Session = ssh.startSession()
    override fun disconnect() { ssh.disconnect() }
}

/**
 * 默认工厂：建连参数与既有逻辑一致（keepalive 20s / maxAliveCount 30 ≈600s、
 * connectTimeout 15s、交互超时关闭）。复用 [AsshHostKeyVerifier] 走同一 known_hosts 校验。
 */
class SshjAgentClientFactory(private val knownHostDao: KnownHostDao) : AgentSshClientFactory {
    override suspend fun connect(cfg: ResolvedHostConfig): AgentSshClient = withContext(Dispatchers.IO) {
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
        SshjAgentClient(c)
    }
}
