package com.assh.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.assh.AsshApp
import com.assh.R
import com.assh.ai.AgentPhase
import com.assh.ai.AgentState
import com.assh.ui.MainActivity
import kotlinx.coroutines.flow.map

/** AI 执行与可续会话的前台通知；等待用户时保留通知，但释放 CPU 唤醒锁。 */
class AgentForegroundService : SessionForegroundService() {
    private val engine get() = (application as AsshApp).sshAgentEngine
    override val foregroundSession get() = engine.foregroundSession
    override val notificationId = 2
    override val channelId = CHANNEL_ID
    override val channelName = "AI 运维任务"
    internal override val workChanges get() = engine.state.map { it.foregroundWork() }
    internal override fun currentWork() = engine.state.value.foregroundWork()

    override fun isStopAction(action: String?) = action == ACTION_STOP
    override fun stopByUser() = engine.cancel()
    override fun protectionLost(generation: Long, reason: String) = engine.onBackgroundUnavailable(generation, reason)

    override fun notification(description: String): Notification {
        val contentIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 1,
            Intent(this, AgentForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.hrlssh)
            .setContentTitle("AI 运维")
            .setContentText(description)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "停止本轮", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "assh_agent"
        const val ACTION_STOP = "com.assh.action.STOP_AGENT"

        fun start(context: Context, generation: Long) {
            context.startForegroundService(Intent(context, AgentForegroundService::class.java)
                .putExtra(EXTRA_GENERATION, generation))
        }
    }
}

internal fun AgentState.foregroundWork(): ForegroundWork {
    val cpu = phase == AgentPhase.CONNECTING || phase == AgentPhase.THINKING ||
        phase == AgentPhase.EXECUTING || (phase == AgentPhase.AWAITING_FOLLOWUP && reconnecting)
    val description = when {
        phase == AgentPhase.WAITING_CONFIRM -> "等待确认命令，点击返回处理"
        phase == AgentPhase.WAITING_HOSTKEY -> "等待确认服务器指纹，点击返回处理"
        reconnecting -> "连接已断开，正在尝试重连"
        phase == AgentPhase.AWAITING_FOLLOWUP -> "本轮已停止或完成，等待追加指令"
        cpu -> "正在执行第 $step 步，点击查看进度"
        else -> "会话已结束"
    }
    return ForegroundWork(sessionActive, cpu, description)
}
