package com.assh.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 内存 [SshTransport]，让 [SshConnectionManager] 的去重/重连/清理/状态聚合逻辑
 * 无需真实 socket 即可单测（C2 seam 的测试 adapter）。
 *
 * connect() 默认直接置 CONNECTED;传 [failWith] 可模拟连接失败;
 * dropConnection() 模拟意外断线;记录 close/connect 调用次数供断言。
 */
class FakeSshTransport(
    override val hostId: Long,
    private val failWith: Throwable? = null
) : SshTransport {

    private val _state = MutableStateFlow(ConnState.IDLE)
    override val state: StateFlow<ConnState> = _state

    override var lastError: String? = null
        private set

    override val stdout: InputStream = ByteArrayInputStream(ByteArray(0))
    override val stdin: OutputStream = ByteArrayOutputStream()

    var connectCount = 0; private set
    var closeCount = 0; private set
    var lastCloseByUser: Boolean? = null; private set
    var lastResize: Pair<Int, Int>? = null; private set

    override suspend fun connect(cfg: ResolvedHostConfig, cols: Int, rows: Int) {
        connectCount++
        _state.value = ConnState.CONNECTING
        failWith?.let {
            lastError = it.message
            _state.value = ConnState.ERROR
            throw it
        }
        _state.value = ConnState.CONNECTED
    }

    override fun resize(cols: Int, rows: Int) { lastResize = cols to rows }

    override fun markDisconnected() {
        if (_state.value == ConnState.CONNECTED) _state.value = ConnState.DISCONNECTED
    }

    override fun close(byUser: Boolean) {
        closeCount++
        lastCloseByUser = byUser
        if (_state.value != ConnState.ERROR) _state.value = ConnState.DISCONNECTED
    }

    /** 测试辅助：模拟意外断线 */
    fun dropConnection() { _state.value = ConnState.DISCONNECTED }
}
