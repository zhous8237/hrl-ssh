package com.assh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.assh.AsshApp
import com.assh.R
import com.assh.ui.MainActivity

/**
 * AI 运维任务专用前台 Service：任务运行期间挂持久通知，降低 App 退后台后
 * 进程被系统回收的概率，让 Agent 循环能继续执行。
 *
 * 与 [SshForegroundService] 相互独立——后者随交互连接数变化，这个只随 AI 任务存活，
 * 互不干扰。由 [com.assh.ai.SshAgentEngine] 在任务开始 / 结束时启停。
 */
class AgentForegroundService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "assh_agent"
        private const val NOTI_ID = 2
        private const val ACTION_STOP = "com.assh.action.STOP_AGENT"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AgentForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            (application as AsshApp).sshAgentEngine.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        // 进程被杀后不复活：AI 任务依赖内存中的 SSH 连接与对话历史，无法恢复
        return START_NOT_STICKY
    }

    /** 用户从最近任务划掉 App：终止任务并停服务 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as AsshApp).sshAgentEngine.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTI_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AgentForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.hrlssh)
            .setContentTitle("AI 运维进行中")
            .setContentText("正在后台执行任务，点此查看进度")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "停止", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "AI 运维任务", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "AI 后台执行运维任务时的前台通知" }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}
