package com.assh.terminal

import com.assh.ssh.SshTransport
import com.termux.terminal.TerminalSession
import java.io.InputStream
import java.io.OutputStream

/**
 * 终端桥接（文档 §8）：把 SSH 流接到 Termux TerminalSession 的 SessionTransport 上。
 *
 * 数据流：
 *   SSH stdout → TerminalSession 读线程 → emulator.append → TerminalView 渲染
 *   TerminalView 按键/IME → TerminalSession.write → 本 transport 输出流 → SSH stdin
 *   TerminalView 尺寸变化 → updateSize → onResize → shell.changeWindowDimensions
 */
class SshSessionTransport(private val ssh: SshTransport) : TerminalSession.SessionTransport {

    override fun getInputStream(): InputStream = ssh.stdout

    override fun getOutputStream(): OutputStream = ssh.stdin

    override fun onResize(columns: Int, rows: Int) {
        ssh.resize(columns, rows)
    }

    override fun close() {
        ssh.markDisconnected()
        ssh.close()
    }
}
