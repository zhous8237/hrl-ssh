package com.assh.terminal

import com.termux.terminal.TerminalSession
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程级终端会话注册表：TerminalSession 必须跨导航/Activity 重建存活
 * （会话生命周期与 SSH 连接对齐，而非与 UI 对齐）。
 */
class TerminalSessionRegistry {

    private val sessions = ConcurrentHashMap<Long, TerminalSession>()

    fun get(hostId: Long): TerminalSession? = sessions[hostId]

    fun put(hostId: Long, session: TerminalSession) {
        sessions[hostId] = session
    }

    fun remove(hostId: Long): TerminalSession? = sessions.remove(hostId)
}
