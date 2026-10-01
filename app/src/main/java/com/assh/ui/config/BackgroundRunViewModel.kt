package com.assh.ui.config

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.assh.AsshApp
import com.assh.service.AgentForegroundService
import com.assh.service.ForegroundSession
import com.assh.service.SshForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BackgroundSystemState(
    val batteryExempt: Boolean,
    val notificationsEnabled: Boolean,
    val agentChannelEnabled: Boolean,
    val terminalChannelEnabled: Boolean
)

fun readBackgroundSystemState(context: Context): BackgroundSystemState {
    val power = context.getSystemService(PowerManager::class.java)
    val notifications = context.getSystemService(NotificationManager::class.java)
    fun channelEnabled(id: String) = notifications.getNotificationChannel(id)?.importance != NotificationManager.IMPORTANCE_NONE
    return BackgroundSystemState(
        batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName),
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        agentChannelEnabled = channelEnabled(AgentForegroundService.CHANNEL_ID),
        terminalChannelEnabled = channelEnabled(SshForegroundService.CHANNEL_ID)
    )
}

class BackgroundRunViewModel(
    private val context: Context,
    agent: ForegroundSession,
    terminal: ForegroundSession
) : ViewModel() {
    private val _system = MutableStateFlow(readBackgroundSystemState(context))
    val system = _system.asStateFlow()
    val agentStatus = agent.status
    val terminalStatus = terminal.status
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun refresh() { _system.value = readBackgroundSystemState(context) }

    fun notificationResult(granted: Boolean) {
        refresh()
        _message.value = if (granted) "已允许通知" else "未允许通知，后台服务仍可按系统规则运行；可到系统设置开启通知"
    }

    fun openBatterySettings() = openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    fun openNotificationSettings(channelId: String? = null) {
        val intent = Intent(if (channelId == null) Settings.ACTION_APP_NOTIFICATION_SETTINGS else Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (channelId != null) intent.putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        openSettings(intent)
    }

    private fun openSettings(intent: Intent) {
        _message.value = null
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                _message.value = "已打开应用详情，请在系统中选择电池或通知设置"
            } catch (_: Exception) {
                _message.value = "无法打开系统设置，请手动进入系统设置中的本应用详情"
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as AsshApp
                BackgroundRunViewModel(app, app.sshAgentEngine.foregroundSession, app.terminalForegroundSession)
            }
        }
    }
}
