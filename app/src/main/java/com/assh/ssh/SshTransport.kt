package com.assh.ssh

import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream

/**
 * 单个 SSH 交互会话的 seam（C2 深化）：把"一条 PTY shell 连接"的能力收在一个小接口后面。
 *
 * 生产实现是 sshj 支撑的 [SshSession];测试可注入内存 fake，从而让 [SshConnectionManager]
 * 的去重 / 重连 / 状态聚合 / teardown 逻辑无需真实 socket 即可单测。
 *
 * 出站操作（write/resize/close）的线程安全与幂等约定见 [SshSession] 的实现注释——
 * 接口本身只承诺：实现负责把网络 I/O 移出主线程、close 幂等、关闭后丢弃后续出站。
 */
interface SshTransport {
    val hostId: Long

    /** 连接状态流，供 UI 与管理器聚合订阅 */
    val state: StateFlow<ConnState>

    /** state==ERROR/DISCONNECTED 时供 UI 展示的原因 */
    val lastError: String?

    /** PTY shell 的输出流（远端 → 本地）；connect 成功后可读 */
    val stdout: InputStream

    /** PTY shell 的输入流（本地 → 远端）；connect 成功后可写 */
    val stdin: OutputStream

    suspend fun connect(cfg: ResolvedHostConfig, cols: Int = 80, rows: Int = 24)

    /** 远端窗口尺寸变更（防抖 + 移出主线程由实现保证） */
    fun resize(cols: Int, rows: Int)

    /** 读线程 EOF / IOException 时标记断开 */
    fun markDisconnected()

    /** 关闭会话；[byUser] 区分用户主动断开（重连判据）。必须幂等。 */
    fun close(byUser: Boolean = false)
}

/**
 * 建立 [SshTransport] 的工厂。生产实现见 [SshjTransportFactory];测试注入 fake 工厂。
 */
fun interface SshTransportFactory {
    fun create(hostId: Long): SshTransport
}
