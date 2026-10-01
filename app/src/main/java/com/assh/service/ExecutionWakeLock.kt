package com.assh.service

import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 有效工作持续时续租，停止时立即释放；超时只作为异常退出的最后保险。 */
class ExecutionWakeLock(
    private val scope: CoroutineScope,
    private val lock: PowerManager.WakeLock,
    private val onFailure: (Exception) -> Unit
) : AutoCloseable {
    private var renewal: Job? = null

    init { lock.setReferenceCounted(false) }

    fun update(required: Boolean) {
        if (!required) {
            close()
            return
        }
        if (renewal?.isActive == true) return
        if (!acquire()) return
        renewal = scope.launch {
            while (isActive) {
                delay(RENEW_MS)
                if (!acquire()) break
            }
        }
    }

    private fun acquire(): Boolean = try {
        lock.acquire(LEASE_MS)
        true
    } catch (e: Exception) {
        close()
        onFailure(e)
        false
    }

    override fun close() {
        renewal?.cancel()
        renewal = null
        if (lock.isHeld) lock.release()
    }

    companion object {
        const val LEASE_MS = 120_000L
        const val RENEW_MS = 60_000L
    }
}
