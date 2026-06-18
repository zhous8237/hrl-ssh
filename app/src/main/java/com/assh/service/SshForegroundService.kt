package com.assh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.assh.AsshApp
import com.assh.R
import com.assh.ssh.SshConnectionManager
import com.assh.ui.MainActivity
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 前台 Service（文档 §10）：持有活跃 SSH 会话，挂持久通知防止进程被回收。
 * foregroundServiceType = dataSync（SSH 持久连接最贴近的类型）。
 */
class SshForegroundService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "assh_connections"
        private const val NOTI_ID = 1
        private const val ACTION_DISCONNECT_ALL = "com.assh.action.DISCONNECT_ALL"

        fun start(context: Context) {
            val intent = Intent(context, SshForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SshForegroundService::class.java))
        }
    }

    inner class LocalBinder : Binder() {
        val manager: SshConnectionManager
            get() = (application as AsshApp).connectionManager
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()

        // C8：连接数变化时实时刷新通知文案。此前 updateNotification() 无人调用，
        // 计数永久停留在 startForeground 时的快照（连第 2 台/断 1 台都不更新）。
        // 改由 manager.states 流驱动：drop(1) 跳过初始值（startForeground 已用它建过一次）。
        val manager = (application as AsshApp).connectionManager
        lifecycleScope.launch {
            manager.states.drop(1).collect {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTI_ID, buildNotification())
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val manager = (application as AsshApp).connectionManager

        if (intent?.action == ACTION_DISCONNECT_ALL) {
            manager.disconnectAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // intent == null 仅在系统重启残留 Service 时出现（改 START_NOT_STICKY 后基本不再发生）。
        // 若此时已无活跃连接，会话早断、保活无意义：先满足 startForegroundService 契约
        // （启动后 5 秒内必须 startForeground），再立即自停，避免空壳 Service 钉住进程。
        if (intent == null && manager.activeCount == 0) {
            startForegroundCompat()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()
        // START_NOT_STICKY：进程被杀后不复活。SSH 是 TCP 长连接，进程死后会话已不可恢复，
        // 复活只会得到一个连不上的空壳 Service（旧 START_STICKY 正是"关 App 后关不掉"的一环）。
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTI_ID, notification)
        }
    }

    /**
     * 用户从最近任务列表划掉 App：比按 Home（10 分钟后台保活）更强的"关闭"意图。
     * 主动断开所有连接并停服务，确保进程可被系统回收，不残留前台通知。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as AsshApp).connectionManager.disconnectAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(): Notification {
        val manager = (application as AsshApp).connectionManager
        val count = manager.activeCount

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SshForegroundService::class.java).setAction(ACTION_DISCONNECT_ALL),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.hrlssh)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("已连接 $count 台服务器")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "全部断开", disconnectIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "SSH 连接", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "维持 SSH 会话的前台通知" }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}
