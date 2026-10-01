package com.assh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal data class ForegroundWork(val maintain: Boolean, val cpu: Boolean, val description: String)

/** 两个服务共用前台就绪与资源收尾，业务的取消/连接策略仍由各自实现负责。 */
abstract class SessionForegroundService : LifecycleService() {
    protected abstract val foregroundSession: ForegroundSession
    protected abstract val notificationId: Int
    protected abstract val channelId: String
    protected abstract val channelName: String
    internal abstract val workChanges: Flow<ForegroundWork>
    internal abstract fun currentWork(): ForegroundWork
    protected abstract fun notification(description: String): Notification
    protected abstract fun isStopAction(action: String?): Boolean
    protected abstract fun stopByUser()
    protected abstract fun protectionLost(generation: Long, reason: String)
    protected open val keepCpuWhilePreparing: Boolean = false

    private var generation = -1L
    private var latestStartId = 0
    private var finishing = false
    private var observer: Job? = null
    private var wakeLock: ExecutionWakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        latestStartId = startId
        if (isStopAction(intent?.action)) {
            stopByUser()
            foregroundSession.stop(foregroundSession.status.value.generation)
            finishService()
            return START_NOT_STICKY
        }
        val requested = intent?.getLongExtra(EXTRA_GENERATION, -1) ?: -1
        val status = foregroundSession.status.value
        if (requested != status.generation || (!status.active && !status.starting)) {
            if (generation != status.generation || !status.active) stopSelf(startId)
            return START_NOT_STICKY
        }
        generation = requested
        finishing = false
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW))
            val work = currentWork()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(notificationId, notification(work.description), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(notificationId, notification(work.description))
            }
            if (wakeLock == null) {
                val power = getSystemService(PowerManager::class.java)
                wakeLock = ExecutionWakeLock(lifecycleScope,
                    power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hrlssh:$channelId")) { error ->
                    failAndStop("后台唤醒保护失败：${error.message ?: "系统拒绝"}")
                }
            }
            wakeLock?.update(work.cpu || (keepCpuWhilePreparing && status.pendingOperations > 0))
            if (!foregroundSession.markReady(generation)) {
                finishService()
                return START_NOT_STICKY
            }
            observer?.cancel()
            observer = lifecycleScope.launch {
                combine(foregroundSession.status, workChanges.distinctUntilChanged()) { state, current -> state to current }
                    .collect { (state, current) ->
                        if (state.generation != generation) return@collect
                        if (!state.active && !state.starting) {
                            finishService()
                        } else if (!current.maintain && state.pendingOperations == 0) {
                            foregroundSession.stop(generation)
                            finishService()
                        } else {
                            wakeLock?.update(current.cpu || (keepCpuWhilePreparing && state.pendingOperations > 0))
                            if (!finishing) manager.notify(notificationId, notification(current.description))
                        }
                    }
            }
        } catch (e: Exception) {
            failAndStop("无法启用后台服务：${e.message ?: "系统拒绝进入前台"}")
        }
        return START_NOT_STICKY
    }

    private fun failAndStop(reason: String) {
        if (foregroundSession.fail(generation, reason)) protectionLost(generation, reason)
        finishService()
    }

    private fun finishService() {
        finishing = true
        observer?.cancel()
        observer = null
        wakeLock?.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(latestStartId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (startId != latestStartId) return
        failAndStop("系统后台运行时限已到，请返回应用手动恢复；远端命令可能仍在执行")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopByUser()
        foregroundSession.stop(foregroundSession.status.value.generation)
        finishService()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        observer?.cancel()
        wakeLock?.close()
        if (!finishing) {
            val reason = "后台服务已被系统停止，请返回应用手动恢复"
            if (foregroundSession.fail(generation, reason)) protectionLost(generation, reason)
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_GENERATION = "foreground_generation"
    }
}
