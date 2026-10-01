package com.assh.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.assh.AsshApp
import com.assh.R
import com.assh.ssh.ConnState
import com.assh.ssh.SshConnectionManager
import com.assh.ui.MainActivity
import kotlinx.coroutines.flow.map

/** 已连接的交互终端持续保活，不以有无输出猜测远端命令是否仍在执行。 */
class SshForegroundService : SessionForegroundService() {
    private val app get() = application as AsshApp
    private val manager get() = app.connectionManager
    override val foregroundSession get() = app.terminalForegroundSession
    override val notificationId = 1
    override val channelId = CHANNEL_ID
    override val channelName = "SSH 连接"
    override val keepCpuWhilePreparing = true
    internal override val workChanges get() = manager.states.map { work(it) }
    internal override fun currentWork() = work(manager.states.value)

    private fun work(states: Map<Long, ConnState>): ForegroundWork {
        val count = states.values.count { it == ConnState.CONNECTED }
        val needed = states.values.any { it == ConnState.CONNECTED || it == ConnState.CONNECTING }
        return ForegroundWork(needed, needed, if (count > 0) "已连接 $count 台服务器，后台持续维持" else "正在连接服务器")
    }

    inner class LocalBinder : Binder() {
        val manager: SshConnectionManager get() = app.connectionManager
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun isStopAction(action: String?) = action == ACTION_DISCONNECT_ALL
    override fun stopByUser() = manager.disconnectAll()
    override fun protectionLost(generation: Long, reason: String) = manager.stopForBackgroundLimit()

    override fun notification(description: String): Notification {
        val contentIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 1,
            Intent(this, SshForegroundService::class.java).setAction(ACTION_DISCONNECT_ALL), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.hrlssh)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(description)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "全部断开", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "assh_connections"
        const val ACTION_DISCONNECT_ALL = "com.assh.action.DISCONNECT_ALL"

        fun start(context: Context, generation: Long) {
            context.startForegroundService(Intent(context, SshForegroundService::class.java)
                .putExtra(EXTRA_GENERATION, generation))
        }
    }
}
