package com.assh.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

/** 就绪表示系统已接受前台通知，而不只是 startForegroundService 已返回。 */
data class ForegroundStatus(
    val generation: Long = 0,
    val starting: Boolean = false,
    val active: Boolean = false,
    val pendingOperations: Int = 0,
    val error: String? = null
)

class ForegroundUnavailableException(message: String) : IllegalStateException(message)

/** 每种服务一个实例；代次隔离旧回调，连接准备期间的租约防止空服务提前退出。 */
class ForegroundSession(private val startService: (Long) -> Unit) {
    private val _status = MutableStateFlow(ForegroundStatus())
    val status = _status.asStateFlow()
    private var ready = CompletableDeferred<Unit>()

    @Synchronized
    fun request(): Lease {
        var current = _status.value
        if (!current.active && !current.starting) {
            current = ForegroundStatus(generation = current.generation + 1, starting = true, pendingOperations = 1)
            ready = CompletableDeferred()
            _status.value = current
            try {
                startService(current.generation)
            } catch (e: Exception) {
                fail(current.generation, "无法启动后台服务：${e.message ?: "系统拒绝启动"}")
            }
        } else {
            _status.value = current.copy(pendingOperations = current.pendingOperations + 1)
        }
        return Lease(this, current.generation, ready)
    }

    @Synchronized
    fun markReady(generation: Long): Boolean {
        val current = _status.value
        if (current.generation != generation || (!current.starting && !current.active)) return false
        _status.value = current.copy(starting = false, active = true, error = null)
        ready.complete(Unit)
        return true
    }

    @Synchronized
    fun fail(generation: Long, message: String): Boolean {
        val current = _status.value
        if (current.generation != generation || (!current.starting && !current.active)) return false
        _status.value = current.copy(starting = false, active = false, error = message)
        ready.completeExceptionally(ForegroundUnavailableException(message))
        return true
    }

    @Synchronized
    fun stop(generation: Long) {
        val current = _status.value
        if (current.generation != generation) return
        _status.value = current.copy(starting = false, active = false)
        ready.completeExceptionally(CancellationException("后台服务已停止"))
    }

    @Synchronized
    private fun release(generation: Long) {
        val current = _status.value
        if (current.generation == generation) {
            _status.value = current.copy(pendingOperations = (current.pendingOperations - 1).coerceAtLeast(0))
        }
    }

    fun isActive(generation: Long): Boolean = _status.value.let { it.generation == generation && it.active }

    class Lease internal constructor(
        private val owner: ForegroundSession,
        val generation: Long,
        private val ready: CompletableDeferred<Unit>
    ) : AutoCloseable {
        private var closed = false

        suspend fun awaitReady() {
            try {
                withTimeout(10_000) { ready.await() }
            } catch (e: TimeoutCancellationException) {
                owner.fail(generation, "后台服务未及时就绪，请返回应用后重试")
                throw ForegroundUnavailableException("后台服务未及时就绪，请返回应用后重试")
            }
            if (!owner.isActive(generation)) throw ForegroundUnavailableException("后台服务已停止，请重新开始")
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            owner.release(generation)
        }
    }
}
